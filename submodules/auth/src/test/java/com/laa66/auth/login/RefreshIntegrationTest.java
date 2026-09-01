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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.EphemeralSigningKeyConfig;
import com.laa66.auth.support.RedisTestContainer;

import jakarta.servlet.http.Cookie;

/**
 * End-to-end refresh over real Postgres + Redis (Flyway-migrated). Proves the whole rotation slice:
 * a valid refresh cookie + matching {@code X-CSRF-Token} rotates the family (new hash under fam:*,
 * old tok:* kept for replay detection) and mints a new access token; a replay of the pre-rotation
 * token is detected as reuse and revokes the whole family (fam:* deleted); the CSRF double-submit and
 * absent-cookie gates behave; and two concurrent refreshes of the same token do not both succeed.
 * Docker-gated (needs both containers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class RefreshIntegrationTest extends AbstractPostgresIntegrationTest {

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
	void validRefresh_rotatesFamily_keepsOldToken_mintsNewAccess() throws Exception {
		Session session = login(unique("rotate"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;
		String oldHash = sha256Hex(session.refresh);
		assertThat(redis.opsForValue().get(famKey)).isEqualTo(oldHash);

		MvcResult result = mvc.perform(refresh(session.refresh, session.csrf, session.csrf))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andReturn();

		String newRefresh = valueOf(cookie(result, "refresh_token="), "refresh_token");
		String newHash = sha256Hex(newRefresh);

		assertThat(newHash).isNotEqualTo(oldHash);
		// The family pointer actually moved to the new token...
		assertThat(redis.opsForValue().get(famKey)).isEqualTo(newHash);
		// ...the old token entry survives so a later replay is still detectable...
		assertThat(redis.opsForValue().get("auth:refresh:tok:" + oldHash)).isNotNull();
		// ...and the new token is indexed under the SAME family.
		assertThat(redis.opsForValue().get("auth:refresh:tok:" + newHash))
				.isEqualTo(session.userId + ":" + session.familyId);
		// A fresh csrf cookie rides along with the rotation.
		assertThat(cookie(result, "csrf=")).contains("Path=/api/auth").doesNotContain("HttpOnly");
	}

	@Test
	void rotation_keepsFamilyIndexAlive_andRefreshesItsTtl() throws Exception {
		Session session = login(unique("indexttl"));
		String userKey = "auth:refresh:user:" + session.userId;
		long ttlSeconds = java.time.Duration.ofDays(30).toSeconds();

		// Sanity: login indexed the family under user:*.
		assertThat(redis.opsForSet().members(userKey)).contains(session.familyId);

		// Simulate a long-lived silent-refresh session: the index's original login TTL has decayed
		// far below the 30d window (here forced to 60s). Without the H1 EXPIRE in ROTATE, a rotation
		// leaves this shortened TTL untouched and the index eventually expires while the family lives.
		redis.expire(userKey, java.time.Duration.ofSeconds(60));
		assertThat(redis.getExpire(userKey)).isLessThanOrEqualTo(60L);

		mvc.perform(refresh(session.refresh, session.csrf, session.csrf)).andExpect(status().isOk());

		// (a) The family is still indexed (SADD re-adds even if the set had expired between rotations).
		assertThat(redis.opsForSet().members(userKey)).contains(session.familyId);
		// (b) The index TTL jumped back up into the sliding 30d window — proving ROTATE refreshed it,
		// not the stale 60s login-decayed value. Removing the EXPIRE line makes this assertion fail.
		Long refreshedTtl = redis.getExpire(userKey);
		assertThat(refreshedTtl).isGreaterThan(ttlSeconds - 120).isLessThanOrEqualTo(ttlSeconds);
	}

	@Test
	void replayOfRotatedToken_isReuse_revokesFamily_401() throws Exception {
		Session session = login(unique("reuse"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		// First refresh rotates the family (old token now stale).
		mvc.perform(refresh(session.refresh, session.csrf, session.csrf)).andExpect(status().isOk());

		// Presenting the ORIGINAL (pre-rotation) token again is a reuse: uniform 401...
		mvc.perform(refresh(session.refresh, session.csrf, session.csrf))
				.andExpect(status().isUnauthorized());

		// ...and the whole family is revoked — its pointer is gone.
		assertThat(redis.opsForValue().get(famKey)).isNull();
	}

	@Test
	void missingRefreshCookie_withValidCsrf_returns401() throws Exception {
		Session session = login(unique("norefresh"));

		mvc.perform(post("/refresh")
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void csrfHeaderAbsent_returns403() throws Exception {
		Session session = login(unique("nocsrf"));

		mvc.perform(post("/refresh")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));
	}

	@Test
	void csrfHeaderMismatch_returns403() throws Exception {
		Session session = login(unique("badcsrf"));

		mvc.perform(refresh(session.refresh, session.csrf, session.csrf + "x"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));
	}

	@Test
	void concurrentDoubleRefresh_doesNotBothSucceed() throws Exception {
		Session session = login(unique("concurrent"));
		String famKey = "auth:refresh:fam:" + session.userId + ":" + session.familyId;

		CyclicBarrier barrier = new CyclicBarrier(2);
		Callable<Integer> attempt = () -> {
			barrier.await();
			return mvc.perform(refresh(session.refresh, session.csrf, session.csrf)).andReturn()
					.getResponse().getStatus();
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> a = pool.submit(attempt);
			Future<Integer> b = pool.submit(attempt);
			List<Integer> statuses = List.of(a.get(), b.get());

			long successes = statuses.stream().filter(s -> s == 200).count();
			assertThat(successes).isLessThanOrEqualTo(1L);
			assertThat(statuses).contains(401); // the loser is treated as reuse
			// Reuse revokes the family — the atomic single-use guarantee.
			assertThat(redis.opsForValue().get(famKey)).isNull();
		}
		finally {
			pool.shutdownNow();
		}
	}

	private Session login(String email) throws Exception {
		UUID userId = seedUser(email, PASSWORD, true);
		MvcResult result = mvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andReturn();

		String refresh = valueOf(cookie(result, "refresh_token="), "refresh_token");
		String csrf = valueOf(cookie(result, "csrf="), "csrf");
		String tokValue = redis.opsForValue().get("auth:refresh:tok:" + sha256Hex(refresh));
		String familyId = tokValue.substring((userId + ":").length());
		return new Session(userId, familyId, refresh, csrf);
	}

	private static MockHttpServletRequestBuilder refresh(String refreshToken, String csrfCookie, String csrfHeader) {
		return post("/refresh")
				.cookie(new Cookie("refresh_token", refreshToken))
				.cookie(new Cookie("csrf", csrfCookie))
				.header("X-CSRF-Token", csrfHeader);
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

	private record Session(UUID userId, String familyId, String refresh, String csrf) {
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
