package com.laa66.worker.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.RestClient;

import com.laa66.worker.domain.model.ConsumerMustStopException;
import com.laa66.worker.domain.model.ContentMode;
import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PermanentTaskException;
import com.laa66.worker.domain.model.PoiContentKey;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.support.ContentStub;
import com.laa66.worker.support.GeminiStub;

import tools.jackson.databind.JsonNode;

class ContentGenerationResultsAdapterTest {

	/** Nanoseconds, so a hop that rounds anything is visible. */
	private static final Instant TOKEN = Instant.parse("2026-11-09T12:00:00.123456789Z");

	private static final GenerateContentTask TASK =
			new GenerateContentTask(new PoiContentKey("node/42", ContentMode.TEXT, "pl"), TOKEN);

	private final ContentStub content = new ContentStub();

	private final ContentGenerationResultsAdapter adapter = adapter(content.baseUrl());

	@AfterEach
	void stop() {
		content.close();
	}

	@Test
	void theLeaseCheckSendsTheKeyAndTheTokenBitForBit() {
		content.leased("node/42", TOKEN);

		assertThat(adapter.isLeaseLive(TASK)).isTrue();

		String query = URLDecoder.decode(content.leaseQueries().getFirst(), StandardCharsets.UTF_8);
		assertThat(query).isEqualTo("poiId=node/42&mode=TEXT&lang=pl&leasedAt=2026-11-09T12:00:00.123456789Z");
	}

	@Test
	void anotherTokenIsNotLive() {
		content.leased("node/42", TOKEN.plusNanos(1_000));

		assertThat(adapter.isLeaseLive(TASK)).isFalse();
	}

	@Test
	void aCompletionCarriesTheTaskTheTextAndThePromptVersion() {
		adapter.complete(TASK, new GeneratedText("Przewodnik \"po\" Ratuszu", "pl-guide-1"));

		JsonNode result = content.results().getFirst();
		assertThat(result.toString()).isEqualTo("{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},"
				+ "\"leasedAt\":\"2026-11-09T12:00:00.123456789Z\",\"status\":\"COMPLETED\","
				+ "\"text\":\"Przewodnik \\\"po\\\" Ratuszu\",\"promptVersion\":\"pl-guide-1\"}");
	}

	@Test
	void aFailureCarriesTheTaskTheClassAndTheDetail() {
		adapter.fail(TASK, FailedClass.QUOTA, "gemini: HTTP 429");

		assertThat(content.results().getFirst().toString()).isEqualTo(
				"{\"key\":{\"osmId\":\"node/42\",\"mode\":\"TEXT\",\"lang\":\"pl\"},"
						+ "\"leasedAt\":\"2026-11-09T12:00:00.123456789Z\",\"status\":\"FAILED\","
						+ "\"failedClass\":\"QUOTA\",\"failedDetail\":\"gemini: HTTP 429\"}");
	}

	/**
	 * Review M4-11, LOW-3: content never answers 404 or 405 on these routes, so the worker is pointed
	 * at something else and the consumer must stop.
	 */
	@ParameterizedTest
	@CsvSource({ "503, TransientTaskException", "500, TransientTaskException", "400, PermanentTaskException",
			"422, PermanentTaskException", "403, PermanentTaskException", "404, ConsumerMustStopException",
			"405, ConsumerMustStopException" })
	void aStatusIsTransientUnlessContentRefusedTheRequestOrIsNotContent(int status, String expected) {
		try (GeminiStub answering = new GeminiStub()) {
			answering.respondJson(status, "{}");
			ContentGenerationResultsAdapter refusing = adapter(answering.baseUrl());

			for (Runnable call : new Runnable[] { () -> refusing.isLeaseLive(TASK),
					() -> refusing.complete(TASK, new GeneratedText("t", "v")),
					() -> refusing.fail(TASK, FailedClass.TRANSIENT, "d") }) {
				Throwable failure = catchThrowable(call::run);
				assertThat(failure.getClass().getSimpleName()).isEqualTo(expected);
				assertThat(failure).hasMessageStartingWith("content answered HTTP " + status).hasMessageNotContaining("127.0.0.1");
			}
		}
	}

	@Test
	void aStopNamesTheVariableToFix() {
		try (GeminiStub answering = new GeminiStub()) {
			answering.respondJson(404, "{}");

			assertThat(catchThrowable(() -> adapter(answering.baseUrl()).isLeaseLive(TASK)))
					.isExactlyInstanceOf(ConsumerMustStopException.class)
					.hasMessage("content answered HTTP 404 to the lease check")
					.extracting(failure -> ((ConsumerMustStopException) failure).remedy())
					.isEqualTo("point CONTENT_URI at content and restart the worker");
		}
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "{\"live\":\"true\"}", "{\"live\":1}", "{}" })
	void aLeaseAnswerWithoutABooleanIsTransient(String body) {
		try (GeminiStub answering = new GeminiStub()) {
			answering.respondJson(200, body);

			assertThat(catchThrowable(() -> adapter(answering.baseUrl()).isLeaseLive(TASK)))
					.isInstanceOf(TransientTaskException.class);
		}
	}

	@Test
	void aRefusedConnectionIsTransientAndItsMessageCarriesNoUrl() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		Throwable failure = catchThrowable(() -> adapter("http://127.0.0.1:" + closedPort).isLeaseLive(TASK));

		assertThat(failure).isInstanceOf(TransientTaskException.class).hasNoCause()
				.hasMessage("content gave no answer to the lease check");
	}

	private static ContentGenerationResultsAdapter adapter(String baseUrl) {
		return new ContentGenerationResultsAdapter(RestClient.builder().baseUrl(baseUrl).build());
	}
}
