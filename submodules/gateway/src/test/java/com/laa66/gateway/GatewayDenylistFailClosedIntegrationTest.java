package com.laa66.gateway;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.laa66.gateway.support.GatewayItSupport;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpServer;

/**
 * M2-12 fail-closed: with Redis unreachable, the denylist check cannot confirm a token is clean, so a
 * protected route must 401 rather than admit the request. Redis is pointed at a dead local port (a
 * free port claimed then released), so a valid, non-revoked token still fails closed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDenylistFailClosedIntegrationTest {

	private static final String ISSUER = "https://auth.trippie.local";
	private static final String KID = "test-kid";

	private static HttpServer backendStub;
	private static HttpServer jwksStub;
	private static ECKey signingKey;

	@Value("${local.server.port}")
	private int port;

	private WebTestClient client;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws Exception {
		signingKey = new ECKeyGenerator(Curve.P_256).keyID(KID).generate();
		jwksStub = GatewayItSupport.startJwksServer(signingKey);
		backendStub = GatewayItSupport.startEchoBackend();

		int deadPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			deadPort = socket.getLocalPort();
		}

		registry.add("SPATIAL_URI", () -> GatewayItSupport.baseUri(backendStub));
		registry.add("AUTH_URI", () -> GatewayItSupport.baseUri(backendStub));
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> GatewayItSupport.jwksUri(jwksStub));
		registry.add("spring.data.redis.host", () -> "127.0.0.1");
		registry.add("spring.data.redis.port", () -> deadPort);
		// Bound the failure so a refused connection surfaces fast instead of hanging.
		registry.add("spring.data.redis.connect-timeout", () -> "1s");
		registry.add("spring.data.redis.timeout", () -> "2s");
	}

	@AfterAll
	static void stopStubs() {
		if (backendStub != null) {
			backendStub.stop(0);
		}
		if (jwksStub != null) {
			jwksStub.stop(0);
		}
	}

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer()
				.baseUrl("http://localhost:" + port)
				.responseTimeout(Duration.ofSeconds(15))
				.build();
	}

	@Test
	void validToken_withRedisDown_failsClosed() throws Exception {
		String token = GatewayItSupport.mint(signingKey, UUID.randomUUID().toString(), ISSUER,
				UUID.randomUUID().toString(), Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));

		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange()
				.expectStatus().isUnauthorized();
	}

	/**
	 * Health contract (pins {@code management.health.redis.enabled: false}): the gateway keeps serving
	 * with Redis down, so its container healthcheck must stay UP. Re-enabling the Redis health
	 * indicator later would flip this to DOWN with the dead Redis here and turn this test red.
	 */
	@Test
	void healthEndpoint_staysUp_withRedisDown() {
		client.get().uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP");
	}
}
