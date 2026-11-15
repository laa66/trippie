package com.laa66.worker.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.laa66.worker.domain.model.ContentMode;
import com.laa66.worker.domain.model.GenerateContentTask;
import com.laa66.worker.domain.model.PoiContentKey;
import com.laa66.worker.domain.model.TransientTaskException;
import com.laa66.worker.infrastructure.client.ContentGenerationResultsAdapter;
import com.laa66.worker.infrastructure.client.SpatialPoiDetailsAdapter;
import com.laa66.worker.support.GeminiStub;

/**
 * The budget on calls to content and spatial is set on the request factory by hand (BOOT 4 TRAP #2),
 * so it is measured on a real socket that never answers: without {@code setReadTimeout} the JDK
 * client waits forever and these tests hit their own deadline.
 */
class ServiceTimeoutTest {

	private static final Duration DEADLINE = WorkerConfig.SERVICE_TIMEOUT.plusSeconds(3);

	private final GeminiStub silent = new GeminiStub();

	@AfterEach
	void stop() {
		silent.close();
	}

	@Test
	void aSpatialThatNeverAnswersIsTransientWithinTheBudget() {
		silent.hang();
		SpatialPoiDetailsAdapter adapter = new SpatialPoiDetailsAdapter(WorkerConfig.restClient(silent.baseUrl()));

		Throwable failure = assertTimeoutPreemptively(DEADLINE, () -> catchThrowable(() -> adapter.find("node/42")));

		assertThat(failure).isInstanceOf(TransientTaskException.class);
	}

	@Test
	void aContentThatStallsInTheBodyIsTransientWithinTheBudget() {
		silent.stallInBody();
		ContentGenerationResultsAdapter adapter = new ContentGenerationResultsAdapter(WorkerConfig.restClient(silent.baseUrl()));
		GenerateContentTask task = new GenerateContentTask(new PoiContentKey("node/42", ContentMode.TEXT, "pl"),
				Instant.parse("2026-11-09T12:00:00.123456Z"));

		Throwable failure = assertTimeoutPreemptively(DEADLINE, () -> catchThrowable(() -> adapter.isLeaseLive(task)));

		assertThat(failure).isInstanceOf(TransientTaskException.class);
	}
}
