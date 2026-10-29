package com.laa66.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The only guard over {@code management.endpoints.web.base-path: /} +
 * {@code exposure.include: health}: a real HTTP GET on a real port, asserting the body actuator
 * serves. Nothing else in this module would notice those two lines breaking — the compose
 * healthcheck curls {@code /health}, so a wrong base-path means the container never turns healthy,
 * and no service has {@code depends_on: content}, so {@code make up} would come up "fine" with
 * content permanently starting.
 *
 * <p>Runs in the {@code unitTest} bucket (the class name is deliberately NOT *IntegrationTest):
 * the {@code test} profile turns the DB health contributor off, so this needs neither a database
 * nor Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

	@Value("${local.server.port}")
	private int port;

	@Test
	void health_isServedAtRoot_andReportsUp() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}
}
