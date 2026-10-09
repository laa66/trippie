package com.laa66.gateway;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.laa66.gateway.support.GatewayItSupport;
import com.laa66.gateway.support.RedisTestContainer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.sun.net.httpserver.HttpServer;

/**
 * M2-12: the reactive jti denylist check wired into the resource-server decoder (flow 03/05). A live
 * Redis (Testcontainers, image pinned to compose) holds auth's {@code auth:denylist:{jti}} keys.
 * Tokens are minted locally with the key the stub JWKS serves, each with a unique jti so cases do not
 * cross-talk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDenylistIntegrationTest {

	private static final String ISSUER = "https://auth.trippie.local";
	private static final String KID = "test-kid";

	private static HttpServer backendStub;
	private static HttpServer jwksStub;
	private static ECKey signingKey;

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private ReactiveStringRedisTemplate redis;

	private WebTestClient client;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws Exception {
		signingKey = new ECKeyGenerator(Curve.P_256).keyID(KID).generate();
		jwksStub = GatewayItSupport.startJwksServer(signingKey);
		backendStub = GatewayItSupport.startEchoBackend();

		registry.add("SPATIAL_URI", () -> GatewayItSupport.baseUri(backendStub));
		registry.add("AUTH_URI", () -> GatewayItSupport.baseUri(backendStub));
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> GatewayItSupport.jwksUri(jwksStub));
		registry.add("spring.data.redis.host", RedisTestContainer.REDIS::getHost);
		registry.add("spring.data.redis.port", () -> RedisTestContainer.REDIS.getMappedPort(6379));
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
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	@Test
	void tokenNotOnDenylist_isForwarded() throws Exception {
		String sub = UUID.randomUUID().toString();
		String token = validToken(sub, UUID.randomUUID().toString());

		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.path").isEqualTo("/locations/nearby")
				.jsonPath("$.userId").isEqualTo(sub);
	}

	@Test
	void tokenWithDenylistedJti_isUnauthorized() throws Exception {
		String jti = UUID.randomUUID().toString();
		String token = validToken(UUID.randomUUID().toString(), jti);
		// Mirror auth's logout write (M2-08): auth:denylist:{jti} -> "1".
		redis.opsForValue().set("auth:denylist:" + jti, "1").block();

		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange()
				.expectStatus().isUnauthorized();
	}

	/**
	 * M2-19 criterion (21), PINNING WHAT THE STACK ACTUALLY DOES — which is NOT what the criterion
	 * predicted. Read this before changing either assertion.
	 *
	 * <p>Criterion (21) expected a repeat logout carrying an ALREADY-denylisted access token to answer
	 * 204 end-to-end once {@code /api/auth/logout} joined {@code PUBLIC_AUTH_POSTS}, reasoning that
	 * {@code DenylistCheckingJwtDecoder} "no longer runs on this route". It still runs: in the reactive
	 * stack {@code oauth2ResourceServer} installs an {@code AuthenticationWebFilter} that runs BEFORE
	 * {@code AuthorizationWebFilter}, and it authenticates eagerly whenever an {@code Authorization}
	 * header is present. A decode failure — here the denylist hit — goes straight to the
	 * authentication entry point, so {@code permitAll} is never consulted. {@code permitAll} exempts
	 * requests that carry NO bearer (or a wholly valid one); it does not forgive a bearer that fails.
	 *
	 * <p><b>M2-19's feature is unaffected</b>, which is why these assertions are the right ones: the
	 * gui's boot replay sends no {@code Authorization} header at all (criterion 7), and that is the
	 * path the second half of this test pins. The 401 here is also NOT a behaviour change — the same
	 * request answered 401 before the route was relaxed, so there is no flip to accept or reject, and
	 * M2-08's "idempotent (second call still 204)" remains reachable with a live token.
	 *
	 * <p>Consequence for the plan: criterion (21) as written is not satisfiable without making the
	 * resource-server filter skip the public routes, i.e. a custom matcher or an extra filter — which
	 * criterion (16) explicitly forbids. The PLAN text needs an architect's correction; this test
	 * records the measured truth in the meantime.
	 *
	 * <p>{@link #tokenWithDenylistedJti_isUnauthorized} above stays untouched as the proof that the
	 * denylist bites on protected routes.
	 */
	@Test
	void denylistedToken_onLogoutRoute_isStillRefused_whileTheBearerlessReplayPasses() throws Exception {
		String jti = UUID.randomUUID().toString();
		String token = validToken(UUID.randomUUID().toString(), jti);
		// Mirror auth's logout write (M2-08): the jti of a token that already logged out once.
		redis.opsForValue().set("auth:denylist:" + jti, "1").block();

		client.post().uri("/api/auth/logout")
				.header("Authorization", "Bearer " + token)
				.exchange()
				.expectStatus().isUnauthorized();

		// ...and the same token is refused on a protected route too, as it always was.
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange()
				.expectStatus().isUnauthorized();

		// The bearer-less replay on that very route DOES reach auth — the one thing M2-19 needed, and
		// the reason a denylisted bearer being refused above costs the feature nothing.
		client.post().uri("/api/auth/logout")
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.path").isEqualTo("/logout");
	}

	@Test
	void nonDenylistedToken_isConsistentAcrossRequests() throws Exception {
		String token = validToken(UUID.randomUUID().toString(), UUID.randomUUID().toString());

		for (int i = 0; i < 2; i++) {
			client.get().uri("/api/spatial/locations/nearby")
					.header("Authorization", "Bearer " + token)
					.exchange()
					.expectStatus().isOk();
		}
	}

	private static String validToken(String sub, String jti) throws Exception {
		return GatewayItSupport.mint(signingKey, sub, ISSUER, jti,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
	}
}
