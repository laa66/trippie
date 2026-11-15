package com.laa66.worker.infrastructure.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.laa66.worker.domain.port.in.TaskConsumer;
import com.laa66.worker.domain.port.out.GenerationResults;
import com.laa66.worker.domain.port.out.PoiDetails;
import com.laa66.worker.domain.port.out.TextGenerator;
import com.laa66.worker.domain.service.GenerationTaskHandler;
import com.laa66.worker.domain.service.ProviderCallPacer;
import com.laa66.worker.infrastructure.client.ContentGenerationResultsAdapter;
import com.laa66.worker.infrastructure.client.SpatialPoiDetailsAdapter;

@Configuration
public class WorkerConfig {

	/**
	 * Wall-clock budget of one call to content or spatial, connect included: a primary-key read or
	 * write inside the compose network. Wider than content's 1 s on its call to spatial because no
	 * user waits here and a timeout costs a retry with backoff. A constant for the reason
	 * {@code ProviderCallPacer.MIN_PROVIDER_CALL_SPACING} gives.
	 */
	public static final Duration SERVICE_TIMEOUT = Duration.ofSeconds(2);

	@Bean
	ProviderCallPacer.Ticker ticker() {
		return System::nanoTime;
	}

	@Bean
	ProviderCallPacer.Sleeper sleeper() {
		return Thread::sleep;
	}

	/** One per process: the spacing is a property of the provider account, not of a caller. */
	@Bean
	ProviderCallPacer providerCallPacer(ProviderCallPacer.Ticker ticker, ProviderCallPacer.Sleeper sleeper) {
		return new ProviderCallPacer(ticker, sleeper);
	}

	/**
	 * The timeout is set on the request factory by hand: {@code spring.http.client.*} is read by
	 * nobody in Boot 4 without {@code spring-boot-restclient} (BOOT 4 TRAP #2), and the read timeout
	 * also bounds connect (see {@code GeminiConfig#textGenerator}).
	 */
	@Bean
	GenerationResults generationResults(@Value("${trippie.content.uri}") String contentUri) {
		return new ContentGenerationResultsAdapter(restClient(serviceUri("trippie.content.uri", contentUri)));
	}

	@Bean
	PoiDetails poiDetails(@Value("${trippie.spatial.uri}") String spatialUri) {
		return new SpatialPoiDetailsAdapter(restClient(serviceUri("trippie.spatial.uri", spatialUri)));
	}

	@Bean
	TaskConsumer taskConsumer(GenerationResults generationResults, PoiDetails poiDetails, TextGenerator textGenerator) {
		return new GenerationTaskHandler(generationResults, poiDetails, textGenerator);
	}

	/**
	 * Review M4-11, LOW-3: a value that cannot be a service root stops the startup, where at runtime it
	 * would surface only as a failure of every task. The message names the property, not the value,
	 * which may carry credentials.
	 */
	static String serviceUri(String property, String value) {
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			uri = null;
		}
		if (uri == null || uri.getHost() == null
				|| !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
			throw new IllegalStateException(property + " must be an absolute http or https URI with a host");
		}
		return value;
	}

	static RestClient restClient(String baseUrl) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(SERVICE_TIMEOUT);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}
}
