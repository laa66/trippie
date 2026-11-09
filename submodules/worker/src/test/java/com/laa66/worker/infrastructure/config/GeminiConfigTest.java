package com.laa66.worker.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.port.out.TextGenerator;
import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.support.GeminiStub;
import com.laa66.worker.support.ManualClock;

class GeminiConfigTest {

	private static final PoiPrompt PROMPT = new PoiPrompt("Ratusz", PoiCategory.ARCHITECTURE, "pl");

	private final GeminiConfig config = new GeminiConfig();

	private final ManualClock clock = new ManualClock(0);

	private final ProviderCallPacer pacer = new ProviderCallPacer(clock, clock.sleeper());

	@TempDir
	Path dir;

	/** Review M4-09: the pacer is part of the bean the handler gets, not a call the handler makes. */
	@Test
	void theShippedGeneratorIsPaced() throws IOException {
		try (GeminiStub stub = new GeminiStub()) {
			stub.respondJson(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Tekst.\"}]},\"finishReason\":\"STOP\"}]}");
			TextGenerator generator = GeminiConfigAccess.textGenerator(loaded(), pacer, stub.baseUrl());

			generator.generate(PROMPT);
			generator.generate(PROMPT);

			assertThat(stub.requests()).hasSize(2);
			assertThat(clock.sleeps()).containsExactly(ProviderCallPacer.MIN_PROVIDER_CALL_SPACING);
		}
	}

	@Test
	void withoutAKeyTheGeneratorRefusesWithoutAnyCall() throws IOException {
		try (GeminiStub stub = new GeminiStub()) {
			TextGenerator generator = GeminiConfigAccess.textGenerator(
					new ApiKeyFile.Unavailable("GEMINI_API_KEY_FILE is not set"), pacer, stub.baseUrl());

			assertThatIllegalStateException().isThrownBy(() -> generator.generate(PROMPT))
					.withMessageContaining("GEMINI_API_KEY_FILE is not set");
			assertThat(stub.requests()).isEmpty();
		}
	}

	@Test
	void healthIsDownWithTheReasonWhenTheKeyIsUnavailable() {
		Health health = config.geminiHealthIndicator(new ApiKeyFile.Unavailable("/secrets/gemini.key is empty")).health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.getDetails()).containsEntry("reason", "/secrets/gemini.key is empty");
	}

	@Test
	void healthIsUpAndShowsNoKeyMaterialWhenLoaded() throws IOException {
		Health health = config.geminiHealthIndicator(loaded()).health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).isEmpty();
	}

	@Test
	void healthIsDownWithTheReasonOnceTheProviderRejectsTheKey() throws IOException {
		GeminiKeyHealth health = config.geminiHealthIndicator(loaded());

		health.keyRejected("gemini: API key rejected, HTTP 401");
		health.keyRejected("a later one");

		assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.health().getDetails()).containsEntry("reason", "gemini: API key rejected, HTTP 401");
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://generativelanguage.googleapis.com", "http://127.0.0.1:8089", "http://localhost:1" })
	void theKeyMayGoOverHttpsOrToLoopback(String baseUrl) {
		assertThatNoException().isThrownBy(() -> GeminiConfig.requireSafeTransport(baseUrl));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://generativelanguage.googleapis.com", "http://10.0.0.5", "ftp://127.0.0.1" })
	void theKeyNeverGoesOutInPlainTextOverTheNetwork(String baseUrl) {
		assertThatIllegalArgumentException().isThrownBy(() -> GeminiConfig.requireSafeTransport(baseUrl));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "Gemini", "gemini/../x", "gemini:generateContent", "gemini?key=x" })
	void aModelThatIsNotAModelIdFailsTheBoot(String model) {
		assertThatIllegalArgumentException().isThrownBy(
				() -> config.textGenerator(loaded(), pacer, "https://example.org", model, 1024));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 65_537 })
	void anOutputLimitOutsideTheModelsRangeFailsTheBoot(int maxOutputTokens) {
		assertThatIllegalArgumentException().isThrownBy(
				() -> config.textGenerator(loaded(), pacer, "https://example.org", "gemini-3.5-flash-lite", maxOutputTokens));
	}

	@Test
	void theSpacingTheShippedGeneratorUsesIsTheProviderSpacing() {
		assertThat(ProviderCallPacer.MIN_PROVIDER_CALL_SPACING).isEqualTo(Duration.ofSeconds(4));
	}

	private ApiKeyFile loaded() throws IOException {
		Path file = dir.resolve("gemini.key");
		if (!Files.exists(file)) {
			Files.writeString(file, "AIzaFAKE-only-for-tests-0123456789abcdef");
		}
		return ApiKeyFile.read(file.toString());
	}
}
