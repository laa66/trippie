package com.laa66.auth.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.EphemeralSigningKeyConfig;
import com.laa66.auth.support.RedisTestContainer;

/**
 * End-to-end login over real Postgres + Redis (Flyway-migrated). Proves the whole slice: controller
 * -> service -> JPA credential lookup -> BCrypt verify -> access mint -> refresh-family write in
 * Redis. The Redis refresh-token key layout (hashed token, family pointer, TTL) is asserted directly
 * so a regression in the storage contract turns red. Docker-gated (needs both containers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class LoginIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private static final String PASSWORD = "Sup3rSecret!";

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
	void verifiedCredentials_return200_accessToken_andWriteHashedRefreshFamily() throws Exception {
		String email = unique("ok");
		UUID userId = seedUser(email, PASSWORD, true);

		MvcResult result = mvc.perform(login(email, PASSWORD))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn();

		String refreshCookie = cookie(result, "refresh_token=");
		assertThat(refreshCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth");
		String rawRefresh = valueOf(refreshCookie, "refresh_token");

		// The csrf cookie is readable and sits on the origin root so the SPA can echo it — it must NOT
		// share the refresh cookie's narrow /api/auth path.
		assertThat(cookie(result, "csrf=")).contains("Path=/").doesNotContain("Path=/api/auth").doesNotContain("HttpOnly");

		// The raw token never rests in Redis — only its SHA-256 hash, under a family for this user.
		String tokKey = "auth:refresh:tok:" + sha256Hex(rawRefresh);
		String tokValue = redis.opsForValue().get(tokKey);
		assertThat(tokValue).isNotNull().startsWith(userId + ":");

		String familyId = tokValue.substring((userId + ":").length());
		String famKey = "auth:refresh:fam:" + userId + ":" + familyId;
		assertThat(redis.opsForValue().get(famKey)).isEqualTo(sha256Hex(rawRefresh));
		assertThat(redis.opsForSet().isMember("auth:refresh:user:" + userId, familyId)).isTrue();

		Long ttl = redis.getExpire(tokKey, TimeUnit.SECONDS);
		long thirtyDays = 30L * 24 * 3600;
		assertThat(ttl).isNotNull().isGreaterThan(thirtyDays - 60).isLessThanOrEqualTo(thirtyDays);
	}

	@Test
	void unverifiedAccount_returns403_distinct() throws Exception {
		String email = unique("unverified");
		seedUser(email, PASSWORD, false);

		mvc.perform(login(email, PASSWORD))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:email-not-verified"));
	}

	@Test
	void wrongPasswordAndUnknownEmail_returnIdentical401() throws Exception {
		String email = unique("verified");
		seedUser(email, PASSWORD, true);

		MvcResult wrongPassword = mvc.perform(login(email, "WrongPassword!"))
				.andExpect(status().isUnauthorized()).andReturn();
		MvcResult unknownEmail = mvc.perform(login(unique("ghost"), PASSWORD))
				.andExpect(status().isUnauthorized()).andReturn();

		// No enumeration: byte-identical body for a wrong password and a non-existent account.
		assertThat(wrongPassword.getResponse().getContentAsString())
				.isEqualTo(unknownEmail.getResponse().getContentAsString());
		assertThat(wrongPassword.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
	}

	@Test
	void unverifiedAccountWithWrongPassword_returns401_notVerificationGate_noCookie() throws Exception {
		// The verified-gate 403 must stay behind a correct password: an unverified account given a
		// WRONG password returns the plain 401, never the distinct email-not-verified 403.
		String email = unique("unverified-wrongpw");
		seedUser(email, PASSWORD, false);

		MvcResult result = mvc.perform(login(email, "WrongPassword!"))
				.andExpect(status().isUnauthorized())
				// The invalid-credentials 401 carries no problem type; only the verification 403 sets one,
				// so its absence proves the verified-gate did not fire.
				.andExpect(jsonPath("$.type").doesNotExist())
				.andReturn();

		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
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

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
			String email, String password) {
		return post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
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
