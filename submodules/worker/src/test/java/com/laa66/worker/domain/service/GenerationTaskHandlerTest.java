package com.laa66.worker.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.ContentMode;
import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiContentKey;
import com.laa66.worker.domain.model.PoiDetail;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.ProviderCredentialRejectedException;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.domain.port.out.GenerationResults;
import com.laa66.worker.domain.port.out.PoiDetails;
import com.laa66.worker.domain.port.out.TextGenerator;

class GenerationTaskHandlerTest {

	private static final GenerateContentTask TASK = new GenerateContentTask(
			new PoiContentKey("node/42", ContentMode.TEXT, "pl"), Instant.parse("2026-11-09T12:00:00.123456Z"));

	private static final GeneratedText TEXT = new GeneratedText("Przewodnik po Ratuszu", "pl-guide-1");

	private final FakeResults results = new FakeResults();

	private final FakeDetails details = new FakeDetails();

	private final FakeGenerator generator = new FakeGenerator();

	private final GenerationTaskHandler handler = new GenerationTaskHandler(results, details, generator);

	@Test
	void aLiveLeaseGoesThroughDetailAndProviderToTheWriteBack() {
		handler.consume(TASK);

		assertThat(results.leaseChecks).containsExactly(TASK);
		assertThat(details.requested).containsExactly("node/42");
		assertThat(generator.prompts).containsExactly(new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, "pl"));
		assertThat(results.completions).containsExactly(Map.entry(TASK, TEXT));
	}

	/** Covers a COMPLETED row, a stale token and an expired lease alike: content answers "no" to each. */
	@Test
	void aLeaseThatIsNoLongerLiveCostsNothingAndIsAcknowledged() {
		results.live = false;

		handler.consume(TASK);

		assertThat(details.requested).isEmpty();
		assertThat(generator.prompts).isEmpty();
		assertThat(results.completions).isEmpty();
		assertThat(results.failures).isEmpty();
	}

	@Test
	void anUnknownPoiIsPermanentAndNeverReachesTheProvider() {
		details.detail = id -> Optional.empty();

		assertThat(catchThrowable(() -> handler.consume(TASK))).isInstanceOf(PermanentTaskException.class)
				.hasMessageContaining("node/42");
		assertThat(generator.prompts).isEmpty();
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = { "null, architecture", "'  ', architecture", "Ratusz, null", "Ratusz, castles" })
	void aDetailThePromptCannotBeBuiltFromIsPermanent(String name, String category) {
		details.detail = id -> Optional.of(new PoiDetail(name, category));

		assertThat(catchThrowable(() -> handler.consume(TASK))).isInstanceOf(PermanentTaskException.class);
		assertThat(generator.prompts).isEmpty();
	}

	@Test
	void anUnavailableSpatialIsTransient() {
		details.detail = id -> {
			throw new TransientTaskException("spatial gave no detail of node/42");
		};

		assertThat(catchThrowable(() -> handler.consume(TASK))).isInstanceOf(TransientTaskException.class);
		assertThat(generator.prompts).isEmpty();
	}

	@Test
	void aRejectedCredentialTravelsOnUnchanged() {
		ProviderCredentialRejectedException rejected = new ProviderCredentialRejectedException("gemini: HTTP 401");
		generator.answer = prompt -> {
			throw rejected;
		};

		assertThat(catchThrowable(() -> handler.consume(TASK))).isSameAs(rejected);
		assertThat(results.completions).isEmpty();
		assertThat(results.failures).isEmpty();
	}

	/** LOW-5 (review M4-10): the no-key generator throws outside the port's contract. */
	@Test
	void aFailureOutsideTheGeneratorsContractTravelsOnUnchanged() {
		IllegalStateException noKey = new IllegalStateException("no Gemini API key");
		generator.answer = prompt -> {
			throw noKey;
		};

		assertThat(catchThrowable(() -> handler.consume(TASK))).isSameAs(noKey);
	}

	/** Review M4-09 dopisek: the token checked before every attempt, so a retry may find it dead. */
	@Test
	void aTokenInvalidatedBetweenAttemptsStopsTheNextOneBeforeTheProvider() {
		generator.answer = prompt -> {
			throw new TextGenerationException(FailedClass.TRANSIENT, "gemini: HTTP 503");
		};
		catchThrowable(() -> handler.consume(TASK));
		results.live = false;

		handler.consume(TASK);

		assertThat(generator.prompts).hasSize(1);
		assertThat(results.leaseChecks).hasSize(2);
	}

	/** A paid text is written by the retry, not paid for again. */
	@Test
	void aWriteBackThatFailsIsRetriedWithTheTextAlreadyPaidFor() {
		results.completionFailures = 1;
		assertThat(catchThrowable(() -> handler.consume(TASK))).isInstanceOf(TransientTaskException.class);

		handler.consume(TASK);

		assertThat(generator.prompts).hasSize(1);
		assertThat(results.completions).containsExactly(Map.entry(TASK, TEXT), Map.entry(TASK, TEXT));
	}

	@Test
	void theKeptTextBelongsToItsTaskAlone() {
		results.completionFailures = 1;
		catchThrowable(() -> handler.consume(TASK));
		GenerateContentTask retaken = new GenerateContentTask(TASK.key(), TASK.leasedAt().plusSeconds(400));

		handler.consume(retaken);

		assertThat(generator.prompts).hasSize(2);
	}

	@Test
	void aGivenUpTaskForgetsItsKeptText() {
		results.completionFailures = 1;
		catchThrowable(() -> handler.consume(TASK));
		handler.giveUp(TASK, new TransientTaskException("content gave no answer to the completion"));

		handler.consume(TASK);

		assertThat(generator.prompts).hasSize(2);
	}

	// --- giving up ------------------------------------------------------------------------------

	@Test
	void aGivenUpTaskIsRecordedWithTheClassOfItsLastFailure() {
		handler.giveUp(TASK, new RuntimeException("listener threw",
				new TextGenerationException(FailedClass.QUOTA, "gemini: HTTP 429 daily")));

		assertThat(results.failures).containsExactly(new Failure(TASK, FailedClass.QUOTA, "gemini: HTTP 429 daily"));
	}

	@Test
	void theFirstClassifiedFailureInTheChainDecides() {
		handler.giveUp(TASK, new PermanentTaskException("spatial does not know POI node/42",
				new TransientTaskException("503")));
		handler.giveUp(TASK, new TransientTaskException("spatial gave no detail of node/42"));

		assertThat(results.failures).extracting(Failure::failedClass)
				.containsExactly(FailedClass.PERMANENT, FailedClass.TRANSIENT);
	}

	/** The message of an exception nobody classified may carry a URL; its type does not. */
	@Test
	void anUnclassifiedFailureIsPermanentAndRecordedByTypeOnly() {
		handler.giveUp(TASK, new RuntimeException("wrapper", new IllegalStateException("GET http://spatial:8080/x?key=1")));

		assertThat(results.failures).containsExactly(
				new Failure(TASK, FailedClass.PERMANENT, "unclassified failure: java.lang.IllegalStateException"));
	}

	@Test
	void aWriteBackThatFailsDoesNotEscapeGivingUp() {
		results.failureWriteThrows = true;

		handler.giveUp(TASK, new TransientTaskException("503"));

		assertThat(results.failures).hasSize(1);
	}

	/** Review M4-11 round 2, LOW-2: a stop is not a failure to record, so it is not swallowed. */
	@Test
	void aStopMetWhileRecordingTheFailureEscapesGivingUp() {
		ConsumerMustStopException stop = new ConsumerMustStopException("content answered HTTP 404 to the failure", "fix it");
		results.failureWriteStops = stop;

		assertThat(catchThrowable(() -> handler.giveUp(TASK, new TransientTaskException("503")))).isSameAs(stop);
	}

	// --- fakes ----------------------------------------------------------------------------------

	record Failure(GenerateContentTask task, FailedClass failedClass, String detail) {
	}

	static final class FakeResults implements GenerationResults {

		boolean live = true;

		int completionFailures;

		boolean failureWriteThrows;

		ConsumerMustStopException failureWriteStops;

		final List<GenerateContentTask> leaseChecks = new ArrayList<>();

		final List<Map.Entry<GenerateContentTask, GeneratedText>> completions = new ArrayList<>();

		final List<Failure> failures = new ArrayList<>();

		@Override
		public boolean isLeaseLive(GenerateContentTask task) {
			leaseChecks.add(task);
			return live;
		}

		@Override
		public void complete(GenerateContentTask task, GeneratedText text) {
			completions.add(Map.entry(task, text));
			if (completionFailures > 0) {
				completionFailures--;
				throw new TransientTaskException("content gave no answer to the completion");
			}
		}

		@Override
		public void fail(GenerateContentTask task, FailedClass failedClass, String detail) {
			failures.add(new Failure(task, failedClass, detail));
			if (failureWriteStops != null) {
				throw failureWriteStops;
			}
			if (failureWriteThrows) {
				throw new TransientTaskException("content gave no answer to the failure");
			}
		}
	}

	static final class FakeDetails implements PoiDetails {

		Function<String, Optional<PoiDetail>> detail = id -> Optional.of(new PoiDetail("Ratusz", "architecture"));

		final List<String> requested = new ArrayList<>();

		@Override
		public Optional<PoiDetail> find(String osmId) {
			requested.add(osmId);
			return detail.apply(osmId);
		}
	}

	static final class FakeGenerator implements TextGenerator {

		Function<PoiPrompt, GeneratedText> answer = prompt -> TEXT;

		final List<PoiPrompt> prompts = new ArrayList<>();

		@Override
		public GeneratedText generate(PoiPrompt prompt) {
			prompts.add(prompt);
			return answer.apply(prompt);
		}
	}
}
