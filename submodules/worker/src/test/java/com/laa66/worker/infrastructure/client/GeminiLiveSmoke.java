package com.laa66.worker.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.laa66.worker.domain.model.GeneratedText;
import com.laa66.worker.domain.model.PoiCategory;
import com.laa66.worker.domain.model.PoiPrompt;
import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.infrastructure.config.ApiKeyFile;
import com.laa66.worker.infrastructure.config.GeminiConfigAccess;

/**
 * MANUAL, PAID: one real call to the Gemini API with the user's key. Never part of {@code make test}:
 * the class name matches neither test task's filter and both exclude it, and it runs only with the
 * system property that {@code ./gradlew geminiSmoke} alone sets. Prints the text and its word count;
 * the token counts are in the adapter's "Gemini text generated" log line.
 */
@EnabledIfSystemProperty(named = "trippie.gemini.smoke", matches = "true")
class GeminiLiveSmoke {

	@Test
	void oneRealGeneration() {
		ApiKeyFile keyFile = ApiKeyFile.read(System.getProperty("trippie.gemini.smoke.key-file"));
		if (keyFile instanceof ApiKeyFile.Unavailable(String reason)) {
			throw new AssertionError("no key for the smoke: " + reason);
		}

		GeneratedText generated = GeminiConfigAccess.textGenerator(keyFile,
				new ProviderCallPacer(System::nanoTime, Thread::sleep), "https://generativelanguage.googleapis.com")
				.generate(new PoiPrompt("Hala Stulecia", PoiCategory.ARCHITECTURE, "pl"));

		int words = generated.text().split("\\s+").length;
		System.out.println("=== SMOKE text (" + words + " words, " + generated.text().length() + " chars, prompt "
				+ generated.promptVersion() + ") ===\n" + generated.text() + "\n=== end ===");
		assertThat(generated.text()).isNotBlank();
	}
}
