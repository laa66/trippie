package com.laa66.content.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Real Tomcat with the datasource pointed at a closed port, so {@code find} fails for real and no
 * Docker is needed. Only a database failure can tell the 503 handler's scope apart from "any
 * runtime exception": spatial is the sole cause that handler may ever report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentReadDatabaseDownTest {

	private static HttpServer spatial;

	private final HttpClient client = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	private int port;

	@BeforeAll
	static void startSpatial() throws IOException {
		spatial = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		spatial.createContext("/locations/", ContentReadDatabaseDownTest::answerUnlockState);
		spatial.start();
	}

	private static void answerUnlockState(HttpExchange exchange) throws IOException {
		boolean unlocked = exchange.getRequestURI().getPath().startsWith("/locations/node/open");
		byte[] body = ("{\"unlocked\":" + unlocked + "}").getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	@AfterAll
	static void stopSpatial() {
		spatial.stop(0);
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("trippie.spatial.uri",
				() -> "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
						+ spatial.getAddress().getPort());
		registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:1/content_db");
		registry.add("spring.datasource.hikari.connection-timeout", () -> "250");
	}

	private HttpResponse<String> get(String poiId) throws Exception {
		return client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/content?poiId=" + poiId + "&mode=TEXT"))
						.header("X-User-Id", UUID.randomUUID().toString())
						.build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void aDatabaseFailureIsAGeneric500AndNotAClaimAboutUnlockState() throws Exception {
		HttpResponse<String> response = get("node/open1");

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.body()).doesNotContain("Unlock state", "127.0.0.1", "jdbc", "Connection",
				"Exception", "poi_content");
	}

	@Test
	void aLockedPoiIsStillA403WhileTheDatabaseIsDown() throws Exception {
		HttpResponse<String> response = get("node/locked1");

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("POI is not unlocked");
	}
}
