package com.laa66.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.laa66.gateway.support.RedisTestContainer;
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
 * Security IT for the reactive resource-server gateway (M2-11, flow 03). Four in-JVM stubs stand in
 * for the compose-only services: ONE BACKEND PER ROUTE TARGET ({@code SPATIAL_URI},
 * {@code CONTENT_URI}, {@code AUTH_URI}), each echoing the forwarded path, the {@code X-User-Id} it
 * received and a {@code backend} marker naming itself, plus a JWKS endpoint serving a
 * locally-generated EC P-256 public key. Tokens are minted locally with the matching private key,
 * mirroring auth's {@code NimbusAccessTokenMinter} (ES256, {@code typ=JWT}, header {@code kid};
 * claims {@code sub/iss/iat/exp/jti}).
 *
 * <p>Why one stub per target rather than one shared stub: a shared echo pins a route's EXISTENCE
 * and its {@code StripPrefix=2}, but not its TARGET. Measured on this suite — pointing the
 * {@code content} route at {@code ${SPATIAL_URI}} left all 22 tests green, while in production the
 * same typo sends every {@code /api/content/**} request to spatial and 404s it. The {@code backend}
 * marker is what makes those mutants fail, and all three route targets now carry one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityIntegrationTest {

	private static final String ISSUER = "https://auth.trippie.local";
	private static final String KID = "test-kid";

	private static HttpServer spatialStub;
	private static HttpServer contentStub;
	private static HttpServer authStub;
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

		// One backend per route target. Each echoes the forwarded path + received X-User-Id (so
		// assertions read the response instead of shared state) and names itself, which is what
		// turns a route pointed at the wrong URI into a failure rather than a green test.
		spatialStub = echoStub("spatial");
		contentStub = echoStub("content");
		authStub = echoStub("auth");

		registry.add("SPATIAL_URI", () -> uriOf(spatialStub));
		registry.add("CONTENT_URI", () -> uriOf(contentStub));
		registry.add("AUTH_URI", () -> uriOf(authStub));
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
				() -> "http://127.0.0.1:" + jwksStub.getAddress().getPort() + "/.well-known/jwks.json");
		// The decoder now runs a denylist EXISTS on every decoded token; point it at a live (empty)
		// Redis so valid tokens here are simply not-denylisted -> forwarded. Denylist-hit and
		// fail-closed behaviour are proven in the dedicated M2-12 ITs.
		registry.add("spring.data.redis.host", RedisTestContainer.REDIS::getHost);
		registry.add("spring.data.redis.port", () -> RedisTestContainer.REDIS.getMappedPort(6379));
	}

	@AfterAll
	static void stopStubs() {
		for (HttpServer stub : new HttpServer[] { spatialStub, contentStub, authStub, jwksStub }) {
			if (stub != null) {
				stub.stop(0);
			}
		}
	}

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
	}

	// ---- open routes -------------------------------------------------------

	/** Also pins the target of the separate {@code spatial-health} route, not just that it is open. */
	@Test
	void health_passesThroughTokenless() {
		client.get().uri("/health").exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.backend").isEqualTo("spatial");
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
				.expectBody()
				.jsonPath("$.backend").isEqualTo("auth")
				.jsonPath("$.path").isEqualTo("/login");
	}

	@Test
	void publicRegister_tokenless_reachesBackend() {
		client.post().uri("/api/auth/register").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.backend").isEqualTo("auth")
				.jsonPath("$.path").isEqualTo("/register");
	}

	/**
	 * M2-19 criteria (17) + (20). This test is the INVERSION of the former
	 * {@code protectedAuthLogout_noToken_isUnauthorized}, sanctioned by the M2-19 ruling (PLAN.md,
	 * M2 frozen decisions, route-matrix revision 2026-10-03) — it is an authorised relaxation, not a
	 * unilaterally loosened security test. {@code /api/auth/logout} joins {@code /refresh} as the
	 * second route authenticated by possession of the refresh credential itself (httpOnly cookie +
	 * double-submit CSRF, both checked by auth), so the gui's boot-time logout replay can destroy a
	 * session it holds no access token for. Criterion (20): auth's own MockMvc cannot prove this —
	 * with the route still protected the replay would 401 at {@code anyExchange().authenticated()}
	 * and revoke nothing, while auth's direct-to-service ITs stayed green.
	 */
	@Test
	void publicAuthLogout_tokenless_reachesBackend() {
		client.post().uri("/api/auth/logout").exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.backend").isEqualTo("auth")
				.jsonPath("$.path").isEqualTo("/logout");
	}

	// ---- protected routes reject bad/absent tokens -------------------------

	@Test
	void protectedSpatial_noToken_isUnauthorized() {
		client.get().uri("/api/spatial/locations/nearby").exchange()
				.expectStatus().isUnauthorized();
	}

	/**
	 * Pins the authorization MATRIX, not the route: measured, this stays green with the
	 * {@code content} route deleted from {@code application.yaml}, because an unrouted path under
	 * {@code anyExchange().authenticated()} also answers 401. The route itself is pinned by
	 * {@link #validToken_toContent_isStrippedAndCarriesSubjectAsUserId()}.
	 */
	@Test
	void protectedContent_noToken_isUnauthorized() {
		client.get().uri("/api/content/content").exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void protectedSettings_noToken_isUnauthorized() {
		client.get().uri("/api/auth/settings").exchange()
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
				.expectBody()
				.jsonPath("$.backend").isEqualTo("spatial")
				.jsonPath("$.path").isEqualTo("/locations/nearby");
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
	void validToken_toContent_isStrippedAndCarriesSubjectAsUserId() throws Exception {
		String sub = UUID.randomUUID().toString();
		String token = mint(signingKey, sub, ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		client.get().uri("/api/content/content")
				.header("Authorization", "Bearer " + token)
				.header("X-User-Id", "attacker")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				// `backend` pins the route TARGET; $.path pins StripPrefix=2; $.userId pins the
				// trust boundary. Without the first, CONTENT_URI -> SPATIAL_URI stays green.
				.jsonPath("$.backend").isEqualTo("content")
				.jsonPath("$.path").isEqualTo("/content")
				.jsonPath("$.userId").isEqualTo(sub);
	}

	@Test
	void publicRoute_stripsSpoofedUserId() {
		client.post().uri("/api/auth/login")
				.header("X-User-Id", "attacker")
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.userId").isEqualTo("");
	}

	/**
	 * M2-19 criterion (19). Pins the exact property the logout relaxation's safety argument rests on:
	 * {@code UserIdHeaderFilter} is registered {@code addFilterAfter(..., AUTHORIZATION)} and strips
	 * the client header UNCONDITIONALLY, so it still runs on a permitAll exchange and a tokenless
	 * {@code /logout} reaches auth with no identity at all. Without this test the relaxation could
	 * silently become an identity-spoofing hole if the filter were ever moved behind the authenticated
	 * branch — nothing else in the suite covers a permitAll POST that is also a mutating operation.
	 */
	@Test
	void publicAuthLogout_tokenless_stripsSpoofedUserId() {
		client.post().uri("/api/auth/logout")
				.header("X-User-Id", "attacker")
				.exchange()
				.expectStatus().isOk()
				.expectBody().jsonPath("$.userId").isEqualTo("");
	}

	// ---- /api/content/internal/** is not routable --------------------------

	@Test
	void internalContentPath_withValidToken_isForbidden() throws Exception {
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		client.post().uri("/api/content/internal/results")
				.header("Authorization", "Bearer " + token)
				.exchange().expectStatus().isForbidden();
	}

	@Test
	void internalContentPath_noToken_isUnauthorized() {
		client.post().uri("/api/content/internal/results").exchange().expectStatus().isUnauthorized();
	}

	/**
	 * What the {@code denyAll} rule really holds, MEASURED here, not reasoned about. Every shape is
	 * sent with the raw path on the wire (JDK {@code HttpClient} over {@code URI.create}, which
	 * neither normalises nor re-encodes), with a valid token and without one:
	 *
	 * <table><caption>bypass shapes</caption>
	 * <tr><th>shape</th><th>with token</th><th>tokenless</th></tr>
	 * <tr><td>{@code /api/content/internal/results}</td><td>403</td><td>401</td></tr>
	 * <tr><td>{@code /api/content/internal} (no trailing segment)</td><td>403</td><td>401</td></tr>
	 * <tr><td>{@code /api/content/Internal/results}, {@code INTERNAL},
	 *     {@code /API/CONTENT/INTERNAL}</td><td>403</td><td>401</td></tr>
	 * <tr><td>{@code /api/content/%69nternal/results}, {@code intern%61l},
	 *     {@code %69%6e%74%65%72%6e%61%6c}, bare {@code %69nternal},
	 *     {@code /%61pi/content/%69nternal/results}</td><td>403</td><td>401</td></tr>
	 * <tr><td>{@code /api/content//internal/results}</td><td>400</td><td>400</td></tr>
	 * <tr><td>{@code /api/content/%2e%2e/internal/results}</td><td>400</td><td>400</td></tr>
	 * <tr><td>{@code /api/content/x/../internal/results}</td><td>400</td><td>400</td></tr>
	 * <tr><td>{@code /api/content/internal%2Fresults}</td><td>400</td><td>400</td></tr>
	 * <tr><td>{@code /api/content/x/%2F../internal/results}</td><td>400</td><td>400</td></tr>
	 * <tr><td>{@code /api/content/internal./results}</td><td><b>200</b></td><td>401</td></tr>
	 * </table>
	 *
	 * <p>The 400s are identical with and without a token and appear on other routes too
	 * ({@code /api/auth//login} is 400 as well), so they come from WebFlux URL parsing BEFORE the
	 * authorization matcher runs, not from this rule: an empty path segment, an encoded dot-segment,
	 * a literal {@code ..} segment and an encoded slash never reach routing at all. That is why the
	 * mismatch Light warned about — Security matching a parsed path while {@code StripPrefix} takes
	 * raw segments — cannot be exploited through those shapes on this stack.
	 *
	 * <p>Residual, measured, and deliberately in this table rather than left out:
	 * {@code /api/content/internal./results} answers 200 and is forwarded as {@code /internal./…}.
	 * {@code internal.} is a DIFFERENT path segment, so no pattern for {@code internal} should match
	 * it, and it is not a way into the backend's {@code /internal/**} either: Spring 6 removed
	 * suffix pattern matching, so {@code @PostMapping("/internal/results")} does not answer
	 * {@code /internal./results}. It is listed because "the gateway denies everything that looks
	 * like internal" would be a false summary of this rule.
	 *
	 * <p>The encoded row is the hole that survived round 2 of the M4-02 review: a matcher folding
	 * {@code getRequest().getPath().value()} to lower case reads the RAW path, so {@code %69nternal}
	 * sailed past it and was forwarded (measured 200 with a trusted {@code X-User-Id}). Unlike the
	 * case variants, Spring MVC would NOT have stopped it downstream — Tomcat decodes before
	 * mapping. See {@code SecurityConfig.CONTENT_INTERNAL}.
	 */
	@Test
	void internalBypassShapes_areRejectedByUrlParsingBeforeAuthorization() throws Exception {
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		for (String raw : List.of(
				"/api/content//internal/results",
				"/api/content/%2e%2e/internal/results",
				"/api/content/x/../internal/results",
				"/api/content/internal%2Fresults",
				"/api/content/x/%2F../internal/results")) {
			assertThat(rawProbe(raw, token)).as("with token: %s", raw).startsWith("400 ");
			assertThat(rawProbe(raw, null)).as("tokenless: %s", raw).startsWith("400 ");
		}
		// Route-independent, i.e. it is URL parsing and not our rule doing the rejecting.
		assertThat(rawProbe("/api/auth//login", null)).startsWith("400 ");
	}

	/** {@code /**}-equivalent: the bare prefix is denied as well, not only paths under it. */
	@Test
	void internalPrefixWithoutTrailingSegment_isDenied() throws Exception {
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		assertThat(rawProbe("/api/content/internal", token)).startsWith("403 ");
	}

	/**
	 * The case fold the default (case-sensitive) {@code PathPattern} parser could not hold: measured
	 * 200 + forwarded before the fix, M4-02 review round 1. Keeps the two doors separate here too: a
	 * valid token is refused by the rule (403), an absent one by the resource-server entry point
	 * (401).
	 */
	@Test
	void caseVariantsOfInternal_areDenied() throws Exception {
		assertDeniedBothWays(
				"/api/content/Internal/results",
				"/api/content/INTERNAL/results",
				"/API/CONTENT/INTERNAL/results");
	}

	/**
	 * The hole that survived round 2 (M4-02 review): a hand-rolled matcher folding the RAW path
	 * could not see through percent-encoding, so {@code %69nternal} was forwarded to content with a
	 * trusted {@code X-User-Id}. A pattern parsed by {@code PathPatternParser} matches DECODED
	 * segments, exactly like the route predicate that decides this request is content's at all —
	 * which is why the two can no longer disagree.
	 *
	 * <p>{@code /%61pi/...} is the decisive shape: it proves the decoding happens before matching on
	 * every segment, not only the one we name.
	 */
	@Test
	void percentEncodedVariantsOfInternal_areDenied() throws Exception {
		assertDeniedBothWays(
				"/api/content/%69nternal/results",
				"/api/content/%49nternal/results",
				"/api/content/%69NTERNAL/results",
				"/api/content/intern%61l/results",
				"/api/content/%69%6e%74%65%72%6e%61%6c/results",
				"/api/content/%69nternal",
				"/%61pi/content/%69nternal/results");
	}

	/**
	 * Residual, pinned as measured so the rule is not summarised as more than it is: {@code internal.}
	 * is a different segment and is forwarded. Not a route to the backend's {@code /internal/**}
	 * (Spring 6 has no suffix pattern matching), and it still needs a token.
	 */
	@Test
	void trailingDotSegment_isForwarded_notDenied() throws Exception {
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		assertThat(rawProbe("/api/content/internal./results", token))
				.startsWith("200 ")
				.contains("\"backend\":\"content\"");
		assertThat(rawProbe("/api/content/internal./results", null)).startsWith("401 ");
	}

	private void assertDeniedBothWays(String... rawPaths) throws Exception {
		String token = mint(signingKey, UUID.randomUUID().toString(), ISSUER,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));
		for (String raw : rawPaths) {
			assertThat(rawProbe(raw, token)).as("with token: %s", raw).startsWith("403 ");
			assertThat(rawProbe(raw, null)).as("tokenless: %s", raw).startsWith("401 ");
		}
	}

	/**
	 * Sends the path EXACTLY as given: {@code URI.create} does not normalise dot-segments and the
	 * JDK client does not re-encode, which {@code WebTestClient}'s URI builder would. Returns
	 * {@code "<status> <body>"}.
	 */
	private String rawProbe(String rawPath, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://localhost:" + port + rawPath))
				.POST(HttpRequest.BodyPublishers.noBody());
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request.build(), HttpResponse.BodyHandlers.ofString());
		return response.statusCode() + " " + response.body();
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

	private static HttpServer echoStub(String backend) throws IOException {
		HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stub.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String userId = exchange.getRequestHeaders().getFirst("X-User-Id");
			respond(exchange, 200, "{\"backend\":\"" + backend + "\",\"path\":\"" + path
					+ "\",\"userId\":\"" + (userId == null ? "" : userId) + "\"}");
		});
		stub.start();
		return stub;
	}

	private static String uriOf(HttpServer stub) {
		return "http://127.0.0.1:" + stub.getAddress().getPort();
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
