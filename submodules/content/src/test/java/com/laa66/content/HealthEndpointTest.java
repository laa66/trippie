package com.laa66.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
 *
 * <p>It also pins that the broker is not part of {@code /health}: the profile points the broker at a
 * closed port, and with the rabbit contributor on, this endpoint answers 503 DOWN. Reads keep
 * working with the broker down, and a DOWN here would turn the compose healthcheck red for an outage
 * that only affects publishing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

	@Value("${local.server.port}")
	private int port;

	@Value("${spring.rabbitmq.host}")
	private String brokerHost;

	@Value("${spring.rabbitmq.port}")
	private int brokerPort;

	@Test
	void health_isServedAtRoot_andReportsUp() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void health_staysUp_withTheBrokerUnreachable() throws Exception {
		assertThatThrownBy(() -> {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(brokerHost, brokerPort), 1_000);
			}
		}).as("this test proves nothing against a reachable broker").isInstanceOf(IOException.class);

		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}
}
