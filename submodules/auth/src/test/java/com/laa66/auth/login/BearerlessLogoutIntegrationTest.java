package com.laa66.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;

import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.EphemeralSigningKeyConfig;
import com.laa66.auth.support.RedisTestContainer;

import jakarta.servlet.http.Cookie;

/**
 * M2-19 (A/B): the BEARER-LESS logout over real Postgres + Redis. This is the server half of the
 * boot-time logout replay — the gui reaches this path after a reload, when the in-memory access token
 * is gone but the httpOnly refresh cookie survived a logout that never got its 204.
 *
 * <p>Deliberately a SEPARATE class from {@link LogoutIntegrationTest}, which M2-19 criterion (2) keeps
 * byte-identical as the proof that the bearer path is unchanged.
 *
 * <p>Covers criterion (1) — tokenless + matching CSRF revokes the family (proven by the old refresh
 * cookie then 401ing at {@code /refresh}), clears both cookies, writes NOTHING under
 * {@code auth:denylist:*}, and answers 204 — criterion (3) (no credential at all is still a clean,
 * write-free 204) and criterion (4) (CSRF stays mandatory with no bearer to fall back on).
 *
 * <p>Note per criterion (20): MockMvc here talks straight to auth, so this proves the auth half only.
 * That the tokenless request survives the gateway at all is proven by
 * {@code GatewaySecurityIntegrationTest}. Docker-gated (needs both containers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class BearerlessLogoutIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private static final String PASSWORD = "Sup3rSecret!";

	private static final ObjectMapper JSON = new ObjectMapper();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.data.redis.host", RedisTestContainer.REDIS::getHost);
		registry.add("spring.data.redis.port", () -> RedisTestContainer.REDIS.getMappedPort(6379));
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private StringRedisTemplate redis;

	private JdbcClient jdbc() {
		return JdbcClient.create(dataSource);
	}

	/**
	 * Criterion (1). The access token is minted and its {@code jti} captured, but the request carries NO
	 * {@code Authorization} header — so the family must die while the denylist stays empty. Asserting
	 * the whole {@code auth:denylist:*} keyspace (not just this jti) is what makes "no denylist write"
	 * real: a fabricated or defaulted key would also fail this.
	 */
	@Test
	void tokenlessLogout_withRefreshCookieAndCsrf_revokesFamily_writesNoDenylistEntry_204() throws Exception {
		Session session = login(unique("bearerless"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		assertThat(redis.opsForValue().get(famKey)).isEqualTo(sha256Hex(session.refresh));
		Set<String> denylistBefore = redis.keys("auth:denylist:*");

		MvcResult result = mvc.perform(post("/logout")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf))
				.andExpect(status().isNoContent())
				.andReturn();

		// (a) the family is gone...
		assertThat(redis.opsForValue().get(famKey)).isNull();
		// ...and that is observable end to end: the surviving refresh cookie can no longer rotate.
		// This single assertion is the whole point of M2-19 — the shared-device session is dead.
		mvc.perform(refresh(session)).andExpect(status().isUnauthorized());

		// (b) nothing was denylisted: no entry for this token's jti, and the keyspace did not grow.
		assertThat(redis.hasKey("auth:denylist:" + session.jti)).isFalse();
		assertThat(redis.keys("auth:denylist:*")).isEqualTo(denylistBefore);

		// (c) both transport cookies are cleared with the same attributes they were set with.
		assertThat(cookie(result, "refresh_token="))
				.contains("Max-Age=0").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
		assertThat(cookie(result, "csrf="))
				.contains("Max-Age=0").contains("SameSite=Strict").contains("Path=/")
				.doesNotContain("Path=/api/auth").doesNotContain("HttpOnly");
	}

	/** Criterion (1), repeat leg: a second tokenless replay of an already-revoked family is still 204. */
	@Test
	void tokenlessLogout_repeated_isIdempotent_204() throws Exception {
		Session session = login(unique("bearerless-idem"));

		mvc.perform(tokenlessLogout(session)).andExpect(status().isNoContent());
		mvc.perform(tokenlessLogout(session)).andExpect(status().isNoContent());

		assertThat(redis.hasKey("auth:denylist:" + session.jti)).isFalse();
	}

	/**
	 * Criterion (3): no bearer AND no refresh cookie — only the csrf pair. Nothing to revoke, nothing
	 * to deny, but still a clean 204 so a client replaying a logout after the cookie already expired is
	 * never stuck retrying.
	 */
	@Test
	void logoutWithNeitherBearerNorRefreshCookie_is204_andWritesNothing() throws Exception {
		Set<String> denylistBefore = redis.keys("auth:denylist:*");
		String csrf = "standalone-csrf-value";

		mvc.perform(post("/logout")
				.cookie(new Cookie("csrf", csrf))
				.header("X-CSRF-Token", csrf))
				.andExpect(status().isNoContent());

		assertThat(redis.keys("auth:denylist:*")).isEqualTo(denylistBefore);
	}

	/**
	 * Criterion (4): with no bearer the CSRF double-submit is the ONLY gate left, so a mismatch must
	 * 403 and revoke nothing. Without this the replay endpoint would be a cross-site logout trigger.
	 */
	@Test
	void tokenlessLogout_withMismatchedCsrf_returns403_andRevokesNothing() throws Exception {
		Session session = login(unique("bearerless-badcsrf"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		mvc.perform(post("/logout")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf + "x"))
				.andExpect(status().isForbidden());

		assertThat(redis.opsForValue().get(famKey)).isNotNull();
		// Still rotatable, i.e. the 403 really fired before any revocation.
		mvc.perform(refresh(session)).andExpect(status().isOk());
	}

	/** Criterion (4): an absent CSRF header is rejected too, not treated as "nothing to compare". */
	@Test
	void tokenlessLogout_withNoCsrfHeader_returns403_andRevokesNothing() throws Exception {
		Session session = login(unique("bearerless-nocsrf"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		mvc.perform(post("/logout")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf)))
				.andExpect(status().isForbidden());

		assertThat(redis.opsForValue().get(famKey)).isNotNull();
	}

	/**
	 * Criterion (5) end to end: a PRESENT but expired/tampered bearer is rejected 401 and must not be
	 * downgraded to the cookie-only path — so the family survives. Pairs with the slice test: there the
	 * verifier is mocked, here the real {@link com.laa66.auth.infrastructure.security.AccessTokenVerifier}
	 * rejects a genuinely tampered token.
	 */
	@Test
	void logoutWithTamperedBearer_returns401_andLeavesTheFamilyAlive() throws Exception {
		Session session = login(unique("bearerless-tampered"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		// Flip the last character of the signature segment: same structure, broken signature.
		String tampered = tamperSignature(session.accessToken);

		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf))
				.andExpect(status().isUnauthorized());

		// NOT revoked: a bad token buys nothing at all, not even the cookie-only revocation.
		assertThat(redis.opsForValue().get(famKey)).isNotNull();
		assertThat(redis.hasKey("auth:denylist:" + session.jti)).isFalse();
	}

	private static String tamperSignature(String compactJwt) {
		int lastDot = compactJwt.lastIndexOf('.');
		String signature = compactJwt.substring(lastDot + 1);
		char first = signature.charAt(0);
		char replacement = (first == 'A') ? 'B' : 'A';
		return compactJwt.substring(0, lastDot + 1) + replacement + signature.substring(1);
	}

	private static MockHttpServletRequestBuilder tokenlessLogout(Session session) {
		return post("/logout")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf);
	}

	private static MockHttpServletRequestBuilder refresh(Session session) {
		return post("/refresh")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf);
	}

	private Session login(String email) throws Exception {
		UUID userId = seedUser(email, PASSWORD, true);
		MvcResult result = mvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();

		String accessToken = JSON.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
		String jti = SignedJWT.parse(accessToken).getJWTClaimsSet().getJWTID();
		String refresh = valueOf(cookie(result, "refresh_token="), "refresh_token");
		String csrf = valueOf(cookie(result, "csrf="), "csrf");
		String tokValue = redis.opsForValue().get("auth:refresh:tok:" + sha256Hex(refresh));
		String familyId = tokValue.substring((userId + ":").length());
		return new Session(userId, familyId, refresh, csrf, accessToken, jti);
	}

	private UUID seedUser(String email, String password, boolean verified) {
		UUID id = UUID.randomUUID();
		jdbc().sql("INSERT INTO app_user (id, email, password_hash, email_verified) VALUES (?, ?, ?, ?)")
				.params(id, email, ENCODER.encode(password), verified)
				.update();
		jdbc().sql("INSERT INTO user_settings (user_id, default_content_mode, selected_categories) "
				+ "VALUES (?, 'BOTH', ARRAY['public_art'])")
				.param(id)
				.update();
		return id;
	}

	private record Session(UUID userId, String familyId, String refresh, String csrf, String accessToken, String jti) {
	}

	private static String cookie(MvcResult result, String prefix) {
		List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return cookies.stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow();
	}

	private static String valueOf(String setCookieHeader, String name) {
		String head = setCookieHeader.split(";", 2)[0];
		return head.substring((name + "=").length());
	}

	private static String unique(String tag) {
		return tag + "-" + UUID.randomUUID() + "@example.com";
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
