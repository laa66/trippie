package com.laa66.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Security IT for the reactive resource-server gateway (M2-11, flow 03). Two in-JVM stubs stand in
 * for the compose-only services: a backend (auth + spatial share it here) that echoes the forwarded
 * path and the {@code X-User-Id} it received, and a JWKS endpoint serving a locally-generated EC
 * P-256 public key. Tokens are minted locally with the matching private key, mirroring auth's
 * {@code NimbusAccessTokenMinter} (ES256, {@code typ=JWT}, header {@code kid}; claims
 * {@code sub/iss/iat/exp/jti}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIntegrationTest {

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

		// Public JWK omits use/alg, exactly like auth's JwksController -> proves the gateway keys
		// off kid + forces ES256 rather than relying on alg hints.
		JWKSet publicJwks = new JWKSet(signingKey.toPublicJWK());
		jwksStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		jwksStub.createContext("/.well-known/jwks.json",
				exchange -> respond(exchange, 200, publicJwks.toString()));
		jwksStub.start();

		// Backend echoes the forwarded path + received X-User-Id so assertions read the response,
		// avoiding shared-state races across requests.
		backendStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		backendStub.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
			respond(exchange, 200, "{\"path\":\"" + path + "\",\"userId\":\""
					+ (userId == null ? "" : userId) + "\"}");
		});
		backendStub.start();

		String backendUri = "http://127.0.0.1:" + backendStub.getAddress().getPort();
		registry.add("SPATIAL_URI", () -> backendUri);
		registry.add("AUTH_URI", () -> backendUri);
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> "http://127.0.0.1:" + jwksStub.getAddress().getPort() + "/.well-known/jwks.json");
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

	// ---- open routes -------------------------------------------------------

	@Test
	void health_passesThroughTokenless() {
		client.get().uri("/health").exchange().expectStatus().isOk();
	}

	@Test
	void gatewaySelfHealth_isServedLocally() {
		client.get().uri("/actuator/health").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
	}

	// ---- public auth POSTs pass tokenless ----------------------------------

	@Test
	void publicLogin_tokenless_reachesBackendStripped() {
		client.post().uri("/api/auth/login").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.path").isEqualTo("/login");
	}

	@Test
	void publicRegister_tokenless_reachesBackend() {
		client.post().uri("/api/auth/register").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.path").isEqualTo("/register");
	}

	// ---- protected routes reject bad/absent tokens -------------------------

	@Test
	void protectedSpatial_noToken_isUnauthorized() {
		client.get().uri("/api/spatial/locations/nearby").exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void protectedSettings_noToken_isUnauthorized() {
		client.get().uri("/api/auth/settings").exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void protectedAuthLogout_noToken_isUnauthorized() {
		client.post().uri("/api/auth/logout").exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void malformedToken_isUnauthorized() {
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer not-a-jwt")
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void wrongSignatureToken_isUnauthorized() throws Exception {
		// Same kid as the real key but signed by a different key -> signature verification fails.
		ECKey attacker = new ECKeyGenerator(Curve.P_256).keyID(KID).generate();
		String token = mint(attacker, UUID.randomUUID().toString(), ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void rs256TokenWithSameKid_isUnauthorized() throws Exception {
		// alg-confusion probe: RSA key published under OUR EC key's kid, token signed RS256. The
		// decoder is pinned to ES256, so it must not verify this with the EC public key -> 401.
		RSAKey rsa = new RSAKeyGenerator(2048).keyID(KID).generate();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(UUID.randomUUID().toString())
				.issuer(ISSUER)
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
				.jwtID(UUID.randomUUID().toString())
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).type(JOSEObjectType.JWT).build(),
				claims);
		jwt.sign(new RSASSASigner(rsa));
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + jwt.serialize())
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void unsecuredAlgNoneToken_isUnauthorized() throws Exception {
		// alg:none unsecured JWT (no signature) must be rejected -> 401.
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(UUID.randomUUID().toString())
				.issuer(ISSUER)
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
				.jwtID(UUID.randomUUID().toString())
				.build();
		String token = new PlainJWT(claims).serialize();
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void validlySignedTokenWithoutSubject_isUnauthorized() throws Exception {
		// Correctly signed by our key, valid iss/exp, but no `sub` -> no identity -> 401 (the
		// subject-presence validator), never a downstream forward or a Netty-path NPE.
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(ISSUER)
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
				.jwtID(UUID.randomUUID().toString())
				.build();
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KID).type(JOSEObjectType.JWT).build(),
				claims);
		jwt.sign(new ECDSASigner(signingKey.toECPrivateKey()));
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + jwt.serialize())
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void wrongIssuerToken_isUnauthorized() throws Exception {
		String token = mint(signingKey, UUID.randomUUID().toString(), "https://evil.example",
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void expiredBeyondSkewToken_isUnauthorized() throws Exception {
		// exp 75 s ago: just past the 60 s skew window -> rejected. Kept tight (not minutes) so a
		// widened skew (e.g. 90 s) fails THIS test instead of slipping through.
		Instant iat = Instant.now().minus(15, ChronoUnit.MINUTES);
		Instant exp = Instant.now().minus(75, ChronoUnit.SECONDS);
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER, iat, exp);
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange().expectStatus().isUnauthorized();
	}

	// ---- valid tokens pass; skew boundary bites ----------------------------

	@Test
	void validToken_isForwarded() throws Exception {
		String sub = UUID.randomUUID().toString();
		String token = mint(signingKey, sub, ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.path").isEqualTo("/locations/nearby");
	}

	@Test
	void expiredWithinSkewToken_isStillValid() throws Exception {
		// exp 30 s ago: inside the 60 s skew window -> honoured. Pins the lower side of the boundary.
		Instant iat = Instant.now().minus(15, ChronoUnit.MINUTES);
		Instant exp = Instant.now().minus(30, ChronoUnit.SECONDS);
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER, iat, exp);
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.exchange().expectStatus().isOk();
	}

	// ---- X-User-Id trust boundary -----------------------------------------

	@Test
	void validToken_stripsSpoofedUserId_andSetsSubjectForDownstream() throws Exception {
		String sub = UUID.randomUUID().toString();
		String token = mint(signingKey, sub, ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		client.get().uri("/api/spatial/locations/nearby")
				.header("Authorization", "Bearer " + token)
				.header("X-User-Id", "attacker")
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.userId").isEqualTo(sub);
	}

	@Test
	void publicRoute_stripsSpoofedUserId() {
		client.post().uri("/api/auth/login")
				.header("X-User-Id", "attacker")
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.userId").isEqualTo("");
	}

	// ---- helpers -----------------------------------------------------------

	private static String mint(ECKey key, String sub, String issuer, Instant iat, Instant exp)
			throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(sub)
				.issuer(issuer)
				.issueTime(Date.from(iat))
				.expirationTime(Date.from(exp))
				.jwtID(UUID.randomUUID().toString())
				.build();
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
				.keyID(key.getKeyID())
				.type(JOSEObjectType.JWT)
				.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		jwt.sign(new ECDSASigner(key.toECPrivateKey()));
		return jwt.serialize();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(bytes);
		}
	}
}
