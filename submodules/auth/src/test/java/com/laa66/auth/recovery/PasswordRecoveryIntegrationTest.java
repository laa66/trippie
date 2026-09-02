package com.laa66.auth.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.EphemeralSigningKeyConfig;
import com.laa66.auth.support.RedisTestContainer;

import jakarta.servlet.http.Cookie;

/**
 * End-to-end forgot/reset over real Postgres + Redis (Flyway-migrated). A capturing mailer stands in
 * for the dev-stub; because issuance is dispatched off the request thread, the mailer offers codes to
 * a queue the test awaits. Every acceptance bullet has an assertion that turns red under the obvious
 * mutation:
 * <ul>
 * <li><b>No enumeration on forgot:</b> it is ALWAYS 200 — for a real account, an unknown email, and a
 * rate-limited account alike — so a known and an unknown email yield the identical 200/200 sequence
 * over two in-window requests. The send-throttle is keyed by the email hash (not the userId), proven
 * by the cooldown/sends keys living under that hash for a real account.</li>
 * <li><b>Reset:</b> a valid RESET OTP writes a new BCrypt hash and revokes ALL of the user's refresh
 * families (every old refresh token now 401, the family index gone); a wrong/expired code or the 6th
 * attempt is a generic 400 with the same counter/invalidate semantics as verify; unknown-email and
 * wrong-code stay indistinguishable 400s.</li>
 * </ul>
 * The direct proof that a new password may not equal the code lives in the validator unit test; here
 * a code-as-password is simply shown to be a 400. Docker-gated (needs both containers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class PasswordRecoveryIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();
	private static final String OLD_PASSWORD = "Sup3rSecret!";
	private static final String NEW_PASSWORD = "Br4ndNewPass!";
	private static final String GENERIC_OTP_DETAIL = "invalid or expired verification code";

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
		registry.add("spring.data.redis.host", RedisTestContainer.REDIS::getHost);
		registry.add("spring.data.redis.port", () -> RedisTestContainer.REDIS.getMappedPort(6379));
	}

	@TestConfiguration
	static class CapturingMailerConfig {

		@Bean
		@Primary
		CapturingOtpMailer capturingOtpMailer() {
			return new CapturingOtpMailer();
		}
	}

	/** Captures RESET codes into a queue, since issuance runs asynchronously off the request thread. */
	static class CapturingOtpMailer implements OtpMailer {

		final BlockingQueue<String> resetCodes = new LinkedBlockingQueue<>();

		@Override
		public void send(OtpPurpose purpose, String recipient, String code) {
			if (purpose == OtpPurpose.RESET) {
				resetCodes.add(code);
			}
		}

		String awaitResetCode() throws InterruptedException {
			String code = resetCodes.poll(5, TimeUnit.SECONDS);
			assertThat(code).as("a RESET OTP should have been issued and mailed").isNotNull();
			return code;
		}

		void assertNoResetCode() throws InterruptedException {
			// No async issuance is ever scheduled on the silent paths, so a short bounded wait is enough.
			assertThat(resetCodes.poll(1, TimeUnit.SECONDS))
					.as("a silent forgot must issue no RESET OTP").isNull();
		}
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private CapturingOtpMailer mailer;

	// --- forgot: always 200, no enumeration -----------------------------------------------------

	@Test
	void forgot_knownEmail_returns200_andIssuesResetOtp() throws Exception {
		UUID userId = seedVerifiedUser(unique("forgot-known"));
		String email = emailOf(userId);

		mvc.perform(forgot(email)).andExpect(status().isOk());
		String code = mailer.awaitResetCode();

		// The OTP is stored under the RESET namespace keyed by the user id, hashed, with a live TTL...
		assertThat(redis.opsForValue().get(resetOtpKey(userId)))
				.as("forgot must issue a hashed RESET code").isEqualTo(sha256Hex(code));
		assertThat(code).matches("\\d{6}");
		assertThat(redis.opsForValue().get("auth:otp:verify:" + userId))
				.as("a reset must not touch the verify namespace").isNull();
		Long ttl = redis.getExpire(resetOtpKey(userId), TimeUnit.SECONDS);
		assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(600L);

		// ...and the send-throttle is keyed by the EMAIL HASH, not the user id (so it works uniformly
		// for existent and non-existent accounts). Bounded by the window TTL (≤ 1 h).
		assertThat(redis.hasKey(throttleCooldownKey(email))).isTrue();
		Long sendsTtl = redis.getExpire(throttleSendsKey(email), TimeUnit.SECONDS);
		assertThat(sendsTtl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(3600L);
	}

	@Test
	void forgot_unknownEmail_returns200_andIssuesNoOtp() throws Exception {
		mvc.perform(forgot(unique("forgot-unknown"))).andExpect(status().isOk());
		mailer.assertNoResetCode();
	}

	@Test
	void forgot_knownAndUnknown_yieldIdentical200Sequence_noThrottleOracle() throws Exception {
		String known = emailOf(seedVerifiedUser(unique("forgot-known-sym")));
		String unknown = unique("forgot-unknown-sym");

		// Two requests each, inside the 60 s cooldown window: the second is throttled either way, yet
		// BOTH accounts produce the identical 200 / 200 — no status oracle for account existence or
		// throttle state. (Mutation check: re-introducing a 429 on the throttled real account makes the
		// known sequence 200/429 while unknown stays 200/200, and this assertion goes red.)
		assertThat(statusSequence(known)).containsExactly(200, 200);
		assertThat(statusSequence(unknown)).containsExactly(200, 200);
	}

	@Test
	void forgot_withinCooldown_stillReturns200_silently() throws Exception {
		UUID userId = seedVerifiedUser(unique("forgot-cd"));
		String email = emailOf(userId);

		mvc.perform(forgot(email)).andExpect(status().isOk());
		mailer.awaitResetCode();

		// Second call inside the cooldown: 200 (never 429) and no new code issued.
		mvc.perform(forgot(email)).andExpect(status().isOk());
		mailer.assertNoResetCode();

		Long cooldownTtl = redis.getExpire(throttleCooldownKey(email), TimeUnit.SECONDS);
		assertThat(cooldownTtl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(60L);
	}

	@Test
	void forgot_pastHourlyCap_stillReturns200_silently() throws Exception {
		UUID userId = seedVerifiedUser(unique("forgot-cap"));
		String email = emailOf(userId);

		// Clear the cooldown between sends to isolate the 5/hour window cap from the 60 s gate.
		for (int i = 1; i <= 5; i++) {
			redis.delete(throttleCooldownKey(email));
			mvc.perform(forgot(email)).andExpect(status().isOk());
			mailer.awaitResetCode();
		}
		assertThat(redis.opsForValue().get(throttleSendsKey(email))).isEqualTo("5");

		// The 6th send is over the cap: still 200, but silent (no code).
		redis.delete(throttleCooldownKey(email));
		mvc.perform(forgot(email)).andExpect(status().isOk());
		mailer.assertNoResetCode();
	}

	// --- reset ----------------------------------------------------------------------------------

	@Test
	void reset_withValidOtp_setsNewHash_revokesAllFamilies_oldRefreshNow401() throws Exception {
		UUID userId = seedVerifiedUser(unique("reset-ok"));
		String email = emailOf(userId);
		String oldHash = passwordHash(userId);

		// Two logins → two distinct refresh families for this user.
		Session s1 = login(email);
		Session s2 = login(email);
		assertThat(redis.opsForSet().members(userIndexKey(userId))).contains(s1.familyId, s2.familyId);

		String code = requestResetCode(email);
		mvc.perform(reset(email, code, NEW_PASSWORD)).andExpect(status().isOk());

		// New BCrypt hash persisted; old password no longer matches, new one does.
		String newHash = passwordHash(userId);
		assertThat(newHash).as("reset must write a new hash").isNotEqualTo(oldHash);
		assertThat(ENCODER.matches(NEW_PASSWORD, newHash)).isTrue();
		assertThat(ENCODER.matches(OLD_PASSWORD, newHash)).isFalse();

		// The reset code was consumed.
		assertThat(redis.opsForValue().get(resetOtpKey(userId))).isNull();

		// ALL families revoked: the index is gone and every old refresh token now 401s.
		assertThat(redis.hasKey(userIndexKey(userId))).isFalse();
		assertThat(redis.opsForValue().get(famKey(userId, s1.familyId))).isNull();
		assertThat(redis.opsForValue().get(famKey(userId, s2.familyId))).isNull();
		mvc.perform(refresh(s1)).andExpect(status().isUnauthorized());
		mvc.perform(refresh(s2)).andExpect(status().isUnauthorized());

		// And the new password actually logs in.
		mvc.perform(loginRequest(email, NEW_PASSWORD)).andExpect(status().isOk());
	}

	@Test
	void reset_wrongOtp_returns400_incrementsCounter_withoutConsumingCode() throws Exception {
		UUID userId = seedVerifiedUser(unique("reset-wrong"));
		String email = emailOf(userId);
		String code = requestResetCode(email);

		mvc.perform(reset(email, wrong(code), NEW_PASSWORD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(GENERIC_OTP_DETAIL));

		assertThat(redis.opsForValue().get(resetOtpKey(userId) + ":attempts")).isEqualTo("1");
		assertThat(redis.opsForValue().get(resetOtpKey(userId)))
				.as("a wrong attempt must not consume the code").isEqualTo(sha256Hex(code));
	}

	@Test
	void reset_sixthAttempt_killsCode_evenWhenCorrect() throws Exception {
		UUID userId = seedVerifiedUser(unique("reset-6th"));
		String email = emailOf(userId);
		String code = requestResetCode(email);

		for (int i = 1; i <= 5; i++) {
			mvc.perform(reset(email, wrong(code), NEW_PASSWORD)).andExpect(status().isBadRequest());
		}
		assertThat(redis.opsForValue().get(resetOtpKey(userId))).isEqualTo(sha256Hex(code));

		// 6th attempt with the CORRECT code still fails and kills the code.
		mvc.perform(reset(email, code, NEW_PASSWORD)).andExpect(status().isBadRequest());

		assertThat(redis.opsForValue().get(resetOtpKey(userId)))
				.as("the 6th attempt must invalidate the code").isNull();
		// The password was NOT changed by any of these failures.
		assertThat(ENCODER.matches(OLD_PASSWORD, passwordHash(userId))).isTrue();
	}

	@Test
	void reset_expiredOrAbsentOtp_returns400() throws Exception {
		UUID userId = seedVerifiedUser(unique("reset-absent"));
		String email = emailOf(userId);
		String code = requestResetCode(email);
		redis.delete(resetOtpKey(userId)); // simulate TTL expiry

		mvc.perform(reset(email, code, NEW_PASSWORD))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(GENERIC_OTP_DETAIL));
		assertThat(ENCODER.matches(OLD_PASSWORD, passwordHash(userId))).isTrue();
	}

	@Test
	void reset_unknownEmail_returns400_identicalToWrongCode_noEnumeration() throws Exception {
		UUID userId = seedVerifiedUser(unique("reset-known"));
		String email = emailOf(userId);
		String code = requestResetCode(email);

		String wrongCodeBody = mvc.perform(reset(email, wrong(code), NEW_PASSWORD))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		String unknownEmailBody = mvc.perform(reset(unique("reset-unknown"), "000000", NEW_PASSWORD))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(unknownEmailBody)
				.as("unknown email and wrong code must be indistinguishable")
				.isEqualTo(wrongCodeBody).contains(GENERIC_OTP_DETAIL);
	}

	@Test
	void reset_newPasswordEqualToCode_returns400_withoutConsumingCode() throws Exception {
		UUID userId = seedVerifiedUser(unique("reset-eqcode"));
		String email = emailOf(userId);
		String code = requestResetCode(email);

		// A password equal to the code is a 400 here (the length floor already excludes it, since a
		// six-digit code is shorter than the 8-char minimum; the cross-field rule itself is proven
		// directly in NewPasswordDiffersFromCodeValidatorTest). Either way it is rejected at the web
		// boundary before the OTP is checked — the code survives and the password is unchanged.
		mvc.perform(reset(email, code, code)).andExpect(status().isBadRequest());
		assertThat(redis.opsForValue().get(resetOtpKey(userId))).isEqualTo(sha256Hex(code));
		assertThat(ENCODER.matches(OLD_PASSWORD, passwordHash(userId))).isTrue();
	}

	@Test
	void reset_tooShortPassword_returns400() throws Exception {
		String email = emailOf(seedVerifiedUser(unique("reset-short")));
		String code = requestResetCode(email);

		mvc.perform(reset(email, code, "short7!")).andExpect(status().isBadRequest());
	}

	@Test
	void reset_over72BytePassword_returns400() throws Exception {
		String email = emailOf(seedVerifiedUser(unique("reset-long")));
		String code = requestResetCode(email);

		mvc.perform(reset(email, code, "a".repeat(73))).andExpect(status().isBadRequest());
	}

	// --- helpers --------------------------------------------------------------------------------

	private record Session(String refresh, String csrf, String familyId) {
	}

	private JdbcClient jdbc() {
		return JdbcClient.create(dataSource);
	}

	private UUID seedVerifiedUser(String email) {
		UUID id = UUID.randomUUID();
		jdbc().sql("INSERT INTO app_user (id, email, password_hash, email_verified) VALUES (?, ?, ?, true)")
				.params(id, email, ENCODER.encode(OLD_PASSWORD))
				.update();
		jdbc().sql("INSERT INTO user_settings (user_id, default_content_mode, selected_categories) "
				+ "VALUES (?, 'BOTH', ARRAY['public_art'])")
				.param(id)
				.update();
		return id;
	}

	private String emailOf(UUID userId) {
		return jdbc().sql("SELECT email FROM app_user WHERE id = ?").param(userId)
				.query(String.class).single();
	}

	private String passwordHash(UUID userId) {
		return jdbc().sql("SELECT password_hash FROM app_user WHERE id = ?").param(userId)
				.query(String.class).single();
	}

	/** Performs a forgot for a real account and returns the RESET code it mails. */
	private String requestResetCode(String email) throws Exception {
		mvc.perform(forgot(email)).andExpect(status().isOk());
		return mailer.awaitResetCode();
	}

	private List<Integer> statusSequence(String email) throws Exception {
		int first = mvc.perform(forgot(email)).andReturn().getResponse().getStatus();
		int second = mvc.perform(forgot(email)).andReturn().getResponse().getStatus();
		mailer.resetCodes.clear(); // this test cares about status only, not the (async) codes
		return List.of(first, second);
	}

	private Session login(String email) throws Exception {
		MvcResult result = mvc.perform(loginRequest(email, OLD_PASSWORD))
				.andExpect(status().isOk()).andReturn();
		String refresh = valueOf(cookie(result, "refresh_token="), "refresh_token");
		String csrf = valueOf(cookie(result, "csrf="), "csrf");
		String tokValue = redis.opsForValue().get("auth:refresh:tok:" + sha256Hex(refresh));
		String familyId = tokValue.substring(tokValue.indexOf(':') + 1);
		return new Session(refresh, csrf, familyId);
	}

	private static MockHttpServletRequestBuilder loginRequest(String email, String password) {
		return post("/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private static MockHttpServletRequestBuilder refresh(Session session) {
		return post("/refresh")
				.cookie(new Cookie("refresh_token", session.refresh))
				.cookie(new Cookie("csrf", session.csrf))
				.header("X-CSRF-Token", session.csrf);
	}

	private static MockHttpServletRequestBuilder forgot(String email) {
		return post("/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}");
	}

	private static MockHttpServletRequestBuilder reset(String email, String code, String newPassword) {
		return post("/reset-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"code\":\"" + code
						+ "\",\"newPassword\":\"" + newPassword + "\"}");
	}

	private static String resetOtpKey(UUID userId) {
		return "auth:otp:" + OtpPurpose.RESET.slug() + ":" + userId;
	}

	private static String throttleCooldownKey(String email) {
		return "auth:otp:" + OtpPurpose.RESET.slug() + ":" + emailHash(email) + ":cooldown";
	}

	private static String throttleSendsKey(String email) {
		return "auth:otp:" + OtpPurpose.RESET.slug() + ":" + emailHash(email) + ":sends";
	}

	private static String userIndexKey(UUID userId) {
		return "auth:refresh:user:" + userId;
	}

	private static String famKey(UUID userId, String familyId) {
		return "auth:refresh:fam:" + userId + ":" + familyId;
	}

	private static String emailHash(String email) {
		return sha256Hex(email.strip().toLowerCase(Locale.ROOT));
	}

	private static String wrong(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private static String unique(String tag) {
		return tag + "-" + UUID.randomUUID() + "@example.com";
	}

	private static String cookie(MvcResult result, String prefix) {
		List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return cookies.stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow();
	}

	private static String valueOf(String setCookieHeader, String name) {
		String head = setCookieHeader.split(";", 2)[0];
		return head.substring((name + "=").length());
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
