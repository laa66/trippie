package com.laa66.worker.infrastructure.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.laa66.worker.domain.port.out.TextGenerator;
import com.laa66.worker.domain.service.PacedTextGenerator;
import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.infrastructure.client.GeminiTextGenerator;

/**
 * The provider's wiring: the key file, the paced adapter, and the health component that says why
 * the worker does not consume.
 *
 * <p>Without a key the worker still boots, but the listener container is never started
 * ({@code MessagingConfig}): the tasks wait in the queue and are consumed after a restart with a
 * key, instead of each one spending its attempts on a call that cannot succeed and poisoning its
 * row. A key the provider refuses at runtime ends the same way, minus the boot: see
 * {@code MessagingConfig}.
 */
@Configuration
public class GeminiConfig {

	private static final Logger log = LoggerFactory.getLogger(GeminiConfig.class);

	/** The documented output limit of gemini-3.5-flash-lite. */
	static final int MAX_OUTPUT_TOKENS_LIMIT = 65_536;

	private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9.-]*");

	@Bean
	ApiKeyFile geminiApiKeyFile(@Value("${trippie.gemini.api-key-file:}") String location) {
		ApiKeyFile keyFile = ApiKeyFile.read(location);
		if (keyFile instanceof ApiKeyFile.Unavailable(String reason)) {
			log.error("Gemini API key unavailable, generation tasks will not be consumed: {}", reason);
		}
		else {
			log.info("Gemini API key loaded from {}", location);
		}
		return keyFile;
	}

	/** Contributes {@code gemini} to {@code /health}. */
	@Bean
	GeminiKeyHealth geminiHealthIndicator(ApiKeyFile geminiApiKeyFile) {
		return new GeminiKeyHealth(geminiApiKeyFile);
	}

	/**
	 * The pacer is applied here, around the adapter, so the handler cannot reach the provider
	 * unpaced. No connect timeout: the read timeout is armed before the connection is made and
	 * bounds the whole exchange (see {@code ContentConfig#poiUnlocks}).
	 */
	@Bean
	TextGenerator textGenerator(ApiKeyFile geminiApiKeyFile, ProviderCallPacer providerCallPacer,
			@Value("${trippie.gemini.base-url}") String baseUrl,
			@Value("${trippie.gemini.model}") String model,
			@Value("${trippie.gemini.max-output-tokens}") int maxOutputTokens) {
		if (!MODEL_ID.matcher(model).matches()) {
			throw new IllegalArgumentException("trippie.gemini.model is not a model ID: " + model);
		}
		if (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS_LIMIT) {
			throw new IllegalArgumentException(
					"trippie.gemini.max-output-tokens must be 1.." + MAX_OUTPUT_TOKENS_LIMIT + ": " + maxOutputTokens);
		}
		requireSafeTransport(baseUrl);
		if (!(geminiApiKeyFile instanceof ApiKeyFile.Loaded(var key))) {
			String reason = ((ApiKeyFile.Unavailable) geminiApiKeyFile).reason();
			return prompt -> {
				throw new IllegalStateException("no Gemini API key: " + reason);
			};
		}
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(GeminiTextGenerator.PROVIDER_TIMEOUT);
		RestClient restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
		return new PacedTextGenerator(providerCallPacer,
				new GeminiTextGenerator(restClient, key, model, maxOutputTokens));
	}

	/** The key goes out with every request, so plain HTTP is allowed only to this machine (test stubs). */
	static void requireSafeTransport(String baseUrl) {
		URI uri = URI.create(baseUrl);
		if ("https".equals(uri.getScheme())) {
			return;
		}
		try {
			if ("http".equals(uri.getScheme()) && uri.getHost() != null
					&& InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
				return;
			}
		}
		catch (UnknownHostException ex) {
			// falls through to the rejection
		}
		throw new IllegalArgumentException("trippie.gemini.base-url must be https (http only to loopback): " + baseUrl);
	}
}
