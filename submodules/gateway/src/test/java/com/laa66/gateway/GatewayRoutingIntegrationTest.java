package com.laa66.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Proves gateway routing against a stub backend standing in for the spatial service.
 * The spatial DNS name only resolves under docker-compose (M0-08), so here SPATIAL_URI
 * is overridden to point the routes at an in-JVM stub on an ephemeral port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIntegrationTest {

	private static HttpServer spatialStub;
	private static final AtomicReference<String> lastForwardedPath = new AtomicReference<>();

	@Value("${local.server.port}")
	private int port;

	private WebTestClient client;

	@DynamicPropertySource
	static void spatialUri(DynamicPropertyRegistry registry) throws IOException {
		spatialStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		spatialStub.createContext("/health", exchange -> respond(exchange, "{\"status\":\"UP\"}"));
		spatialStub.createContext("/", exchange -> {
			lastForwardedPath.set(exchange.getRequestURI().getPath());
			respond(exchange, "{\"forwarded\":\"" + exchange.getRequestURI().getPath() + "\"}");
		});
		spatialStub.start();
		registry.add("SPATIAL_URI", () -> "http://127.0.0.1:" + spatialStub.getAddress().getPort());
	}

	@AfterAll
	static void stopStub() {
		if (spatialStub != null) {
			spatialStub.stop(0);
		}
	}

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void health_passesThroughToSpatial() {
		client.get().uri("/health").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	void apiSpatial_isRoutedToSpatial_withPrefixStripped() {
		client.get().uri("/api/spatial/locations/nearby").exchange()
				.expectStatus().isOk();

		assertThat(lastForwardedPath.get()).isEqualTo("/locations/nearby");
	}

	@Test
	void gatewaySelfHealth_isServedLocally_notProxied() {
		client.get().uri("/actuator/health").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}
