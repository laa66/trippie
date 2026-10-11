package com.laa66.spatial.infrastructure.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.out.LocationRepository;
import com.laa66.spatial.domain.port.out.UserPreferences;
import com.laa66.spatial.domain.service.NearbyLocationsService;
import com.laa66.spatial.infrastructure.client.AuthUserPreferencesAdapter;

/**
 * The module's single wiring point: it binds the framework-free domain service and the outbound
 * HTTP adapter to Spring, keeping {@code @Configuration} knowledge and configuration placeholders
 * out of the domain and out of the adapters themselves.
 */
@Configuration
public class SpatialConfig {

	/**
	 * Wall-clock budget for the whole auth exchange, deliberately a constant and not a property.
	 *
	 * <p>Relaxed binding makes every {@code trippie.auth.*} key env-tunable whether or not anything
	 * says so, and {@code TRIPPIE_AUTH_READ_TIMEOUT=1} does not mean one second — it binds to
	 * {@code PT0.001S}, one millisecond. Every auth call then expires, the fail-closed answer becomes
	 * the only answer, and the {@code requireProximity=false} toggle stops working service-wide while
	 * leaving nothing behind but WARN lines. The budget is part of the fail-closed contract rather
	 * than deployment topology, so {@code trippie.auth.uri} is the only property left.
	 */
	public static final Duration AUTH_TIMEOUT = Duration.ofSeconds(1);

	@Bean
	FindNearbyLocations findNearbyLocations(LocationRepository locationRepository) {
		return new NearbyLocationsService(locationRepository);
	}

	/**
	 * Builds the auth client with its timeout set here rather than left to defaults. Boot 4 splits
	 * {@code RestClientAutoConfiguration} into the {@code spring-boot-restclient} module, which is
	 * not on this module's classpath, so there is no auto-configured {@code RestClient.Builder} and
	 * {@code spring.http.client.*} properties would be read by nobody. Configuring the request
	 * factory by hand is therefore the only thing that actually takes effect — exactly the trap
	 * {@code FlywayAutoConfiguration} set in M1.
	 *
	 * <p><strong>One timeout bounds everything, and it is the read timeout.</strong>
	 * {@code JdkClientHttpRequestFactory} arms its timeout the instant {@code sendAsync} returns,
	 * which is before DNS and the TCP handshake have happened, and disarms it only once the body is
	 * consumed: at expiry it cancels the in-flight future and closes the response body. So the one
	 * value covers connect, the wait for headers and a stall mid-body alike. A connect timeout is
	 * therefore not "the other half of the budget" — it is a second clock inside the first one, and
	 * is not set here: deleting {@code setReadTimeout} makes {@code AuthUserPreferencesTimeoutTest}
	 * fail, deleting a connect timeout makes nothing fail, and configuration that no test can
	 * observe is the kind that quietly stops being true. Established twice on spring-web 7.0.6, not
	 * assumed: read off {@code JdkClientHttpRequest#executeInternal} and its {@code TimeoutHandler},
	 * then measured against a blackholed address, where a connect that can never complete is
	 * cancelled at the budget with no connect timeout configured anywhere.
	 *
	 * <p>The JDK client needs no extra dependency ({@code RestClient} and this factory both ship in
	 * {@code spring-web}) and its blocking {@code get()} unmounts a virtual thread instead of
	 * pinning its carrier, which matters because every caller here arrives on one.
	 */
	@Bean
	UserPreferences userPreferences(@Value("${trippie.auth.uri}") String authUri) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
		requestFactory.setReadTimeout(AUTH_TIMEOUT);

		return new AuthUserPreferencesAdapter(RestClient.builder()
				.baseUrl(authUri)
				.requestFactory(requestFactory)
				.build());
	}
}
