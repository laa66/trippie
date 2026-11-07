package com.laa66.worker;

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
 * The guard over {@code base-path: /} + {@code exposure.include: health}, which the compose
 * healthcheck curls, and over the broker being part of the answer. The UP half needs a broker and
 * lives in {@code WorkerTopologyIntegrationTest}.
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
	void health_isServedAtRoot_andIsDownWithTheBrokerUnreachable() throws Exception {
		assertThatThrownBy(() -> {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(brokerHost, brokerPort), 1_000);
			}
		}).as("this test proves nothing against a reachable broker").isInstanceOf(IOException.class);

		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.body()).contains("\"status\":\"DOWN\"");
	}
}
