package com.laa66.content.infrastructure.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.laa66.content.domain.port.out.PoiUnlocks;
import com.laa66.content.infrastructure.client.SpatialPoiUnlocksAdapter;

/**
 * The module's wiring point for outbound HTTP adapters.
 */
@Configuration
public class ContentConfig {

	/**
	 * Wall-clock budget for the whole spatial exchange, connect included. A constant and not a
	 * property: relaxed binding would read {@code =1} as one millisecond, every call would then
	 * expire and the unlock gate would answer 503 service-wide (see {@code SpatialConfig.AUTH_TIMEOUT}).
	 */
	public static final Duration SPATIAL_TIMEOUT = Duration.ofSeconds(1);

	/**
	 * The timeout is set on the factory by hand because {@code spring-boot-restclient} is not on the
	 * classpath: {@code spring.http.client.*} is read by nobody, silently. No connect timeout: the
	 * read timeout is armed before the connection is made, so it already bounds connect
	 * (see {@code SpatialConfig#userPreferences}).
	 */
	@Bean
	PoiUnlocks poiUnlocks(@Value("${trippie.spatial.uri}") String spatialUri) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(SPATIAL_TIMEOUT);

		return new SpatialPoiUnlocksAdapter(RestClient.builder()
				.baseUrl(spatialUri)
				.requestFactory(requestFactory)
				.build());
	}
}
