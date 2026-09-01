package com.laa66.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
 * End-to-end logout over real Postgres + Redis (Flyway-migrated). Proves the whole revocation slice of
 * flow 05: an authenticated logout (Bearer + refresh cookie + matching {@code X-CSRF-Token}) deletes
 * the refresh family, clears both transport cookies ({@code Max-Age=0}, same attributes), writes the
 * access {@code jti} to {@code auth:denylist:{jti}} with TTL = the token's remaining life, and returns
 * 204; the CSRF double-submit gate holds; and a second identical call is still 204 (idempotent).
 * Docker-gated (needs both containers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class LogoutIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private static final String PASSWORD = "Sup3rSecret!";
	private static final long ACCESS_TTL_SECONDS = 900; // frozen 15-min access TTL
	private static final long DENYLIST_SKEW_SECONDS = 60; // gateway clock skew (auth.jwt.clock-skew)
	// The denylist entry lives for the token's remaining life PLUS the gateway skew.
	private static final long DENYLIST_TTL_SECONDS = ACCESS_TTL_SECONDS + DENYLIST_SKEW_SECONDS;

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

	@Test
	void authenticatedLogout_revokesFamily_denylistsJtiWithRemainingLife_clearsCookies_204() throws Exception {
		Session session = login(unique("logout"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;
		String denylistKey = "auth:denylist:" + session.jti;

		// Preconditions: the family is alive and the jti is not yet denied.
		assertThat(redis.opsForValue().get(famKey)).isEqualTo(sha256Hex(session.refresh));
		assertThat(redis.hasKey(denylistKey)).isFalse();

		MvcResult result = mvc.perform(logout(session))
				.andExpect(status().isNoContent())
				.andReturn();

		// (1) The refresh family is gone — no further silent-refresh is possible...
		assertThat(redis.opsForValue().get(famKey)).isNull();
		// ...proven end-to-end: presenting the old refresh cookie now 401s.
		mvc.perform(refresh(session)).andExpect(status().isUnauthorized());

		// (2) The jti is denylisted with TTL = remaining access life (exp − now ≈ 15 min) + gateway skew.
		// The window is only ~120 s wide and sits at 960, NOT 900 — so it also bites the skew being
		// dropped. That the TTL FOLLOWS the token's exp rather than a hardcoded 15 min is pinned
		// separately (Docker-free) by AccessTokenVerifierTest's short-exp case.
		assertThat(redis.opsForValue().get(denylistKey)).isNotNull();
		Long ttl = redis.getExpire(denylistKey);
		assertThat(ttl).isGreaterThan(DENYLIST_TTL_SECONDS - 120).isLessThanOrEqualTo(DENYLIST_TTL_SECONDS);

		// (3) Both cookies are cleared with Max-Age=0 and the SAME attributes they were set with.
		String refreshCookie = cookie(result, "refresh_token=");
		assertThat(refreshCookie)
				.contains("Max-Age=0").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
		String csrfCookie = cookie(result, "csrf=");
		assertThat(csrfCookie)
				.contains("Max-Age=0").contains("SameSite=Strict").contains("Path=/api/auth").doesNotContain("HttpOnly");
	}

	@Test
	void secondLogout_withSameStillValidToken_isIdempotent_204() throws Exception {
		Session session = login(unique("idempotent"));
		String denylistKey = "auth:denylist:" + session.jti;

		mvc.perform(logout(session)).andExpect(status().isNoContent());
		// Second identical call: family already revoked, jti already denied — still a clean 204, no error.
		mvc.perform(logout(session)).andExpect(status().isNoContent());

		assertThat(redis.opsForValue().get(denylistKey)).isNotNull();
	}

	@Test
	void csrfHeaderMismatch_returns403_andRevokesNothing() throws Exception {
		Session session = login(unique("badcsrf"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken)
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf + "x"))
				.andExpect(status().isForbidden());

		// The 403 fired before any revocation: family intact, jti not denied.
		assertThat(redis.opsForValue().get(famKey)).isNotNull();
		assertThat(redis.hasKey("auth:denylist:" + session.jti)).isFalse();
	}

	private static MockHttpServletRequestBuilder logout(Session session) {
		return post("/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken)
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
