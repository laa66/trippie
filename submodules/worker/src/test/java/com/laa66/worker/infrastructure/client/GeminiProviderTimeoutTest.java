package com.laa66.worker.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.laa66.worker.domain.model.FailedClass;
import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.model.TextGenerationException;
import com.laa66.worker.domain.port.out.TextGenerator;
import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.infrastructure.config.ApiKeyFile;
import com.laa66.worker.infrastructure.config.GeminiConfigAccess;
import com.laa66.worker.support.GeminiStub;
import com.laa66.worker.support.ManualClock;

/**
 * The shipped bean against a provider that never answers: the budget is the real
 * {@link GeminiTextGenerator#PROVIDER_TIMEOUT}, not a test value, so this costs 20 s on purpose. The
 * lower bound rules out a pass by some other, faster failure.
 */
class GeminiProviderTimeoutTest {

	private final GeminiStub stub = new GeminiStub();

	@AfterEach
	void stopStub() {
		stub.close();
	}

	@Test
	@Timeout(60)
	void theShippedGeneratorGivesUpAfterTwentySecondsAsTransient(@TempDir Path dir) throws IOException {
		Path keyFile = Files.writeString(dir.resolve("gemini.key"), GeminiTextGeneratorTest.FAKE_KEY);
		ManualClock clock = new ManualClock(0);
		TextGenerator generator = GeminiConfigAccess.textGenerator(ApiKeyFile.read(keyFile.toString()),
				new ProviderCallPacer(clock, clock.sleeper()), stub.baseUrl());
		stub.hang();

		long start = System.nanoTime();
		TextGenerationException failure = catchThrowableOfType(TextGenerationException.class,
				() -> generator.generate(new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, "pl")));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(failure.failedClass()).isEqualTo(FailedClass.TRANSIENT);
		assertThat(elapsed).isBetween(GeminiTextGenerator.PROVIDER_TIMEOUT.minusMillis(500),
				GeminiTextGenerator.PROVIDER_TIMEOUT.plusSeconds(10));
		assertThat(GeminiTextGenerator.PROVIDER_TIMEOUT).isEqualTo(Duration.ofSeconds(20));
	}
}
