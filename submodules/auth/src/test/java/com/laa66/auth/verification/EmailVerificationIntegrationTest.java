package com.laa66.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.RedisTestContainer;

/**
 * End-to-end verify/resend over real Postgres + Redis (Flyway-migrated). A capturing mailer stands
 * in for the dev-stub so the issued code (and every re-issue) can be checked against Redis. Each
 * invariant — success consumes the code, a wrong code only increments the counter, the 6th attempt
 * kills the code, an absent code is a 400, no enumeration, the 60 s cooldown, the 5/hour cap, resend
 * resets the attempt budget — has an assertion that turns red under the obvious mutation, and the
 * Redis contract (keys + TTLs) is asserted independently of the production code.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationIntegrationTest extends AbstractPostgresIntegrationTest {

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

	static class CapturingOtpMailer implements OtpMailer {

		final AtomicInteger sends = new AtomicInteger();
		volatile String code;

		@Override
		public void send(OtpPurpose purpose, String recipient, String code) {
			this.code = code;
			this.sends.incrementAndGet();
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

	// --- verify ---------------------------------------------------------------------------------

	@Test
	void correctOtpWithinTtl_marksVerified_andConsumesCode() throws Exception {
		Registered reg = register(unique("verify-ok"));

		mvc.perform(verify(reg.email, reg.code)).andExpect(status().isOk());

		assertThat(emailVerified(reg.userId)).isTrue();
		assertThat(redis.opsForValue().get(otpKey(reg.userId)))
				.as("a consumed code must be deleted from Redis").isNull();
		assertThat(redis.opsForValue().get(attemptsKey(reg.userId)))
				.as("the attempt counter must be cleaned up on success").isNull();
	}

	@Test
	void wrongOtp_incrementsAttemptCounter_andDoesNotConsumeCode() throws Exception {
		Registered reg = register(unique("verify-wrong"));

		mvc.perform(verify(reg.email, wrong(reg.code))).andExpect(status().isBadRequest());

		assertThat(redis.opsForValue().get(attemptsKey(reg.userId)))
				.as("a wrong attempt must bump the counter").isEqualTo("1");
		// The counter must inherit the code's remaining TTL so it expires with the code and can
		// never orphan an account. Asserted independently of the OTP key's own TTL.
		Long attemptsTtl = redis.getExpire(attemptsKey(reg.userId), TimeUnit.SECONDS);
		assertThat(attemptsTtl)
				.as("attempt counter must inherit the OTP's remaining TTL, not live forever")
				.isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(600L);
		assertThat(redis.opsForValue().get(otpKey(reg.userId)))
				.as("a wrong attempt must not consume the code")
				.isEqualTo(sha256Hex(reg.code));
		assertThat(emailVerified(reg.userId)).isFalse();
	}

	@Test
	void sixthAttempt_killsCode_evenWhenCorrect() throws Exception {
		Registered reg = register(unique("verify-6th"));

		for (int i = 1; i <= 5; i++) {
			mvc.perform(verify(reg.email, wrong(reg.code))).andExpect(status().isBadRequest());
		}
		assertThat(redis.opsForValue().get(attemptsKey(reg.userId))).isEqualTo("5");
		assertThat(redis.opsForValue().get(otpKey(reg.userId)))
				.as("code still alive after exactly 5 failed attempts").isEqualTo(sha256Hex(reg.code));

		// 6th attempt with the CORRECT code still fails and kills the code.
		mvc.perform(verify(reg.email, reg.code)).andExpect(status().isBadRequest());

		assertThat(redis.opsForValue().get(otpKey(reg.userId)))
				.as("the 6th attempt must invalidate the code").isNull();
		assertThat(redis.opsForValue().get(attemptsKey(reg.userId))).isNull();
		assertThat(emailVerified(reg.userId)).isFalse();

		// And the killed code is truly dead: resubmitting it is a plain 400 (absent).
		mvc.perform(verify(reg.email, reg.code)).andExpect(status().isBadRequest());
	}

	@Test
	void expiredOrAbsentOtp_returns400() throws Exception {
		Registered reg = register(unique("verify-absent"));
		redis.delete(otpKey(reg.userId)); // simulate TTL expiry

		mvc.perform(verify(reg.email, reg.code))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(GENERIC_OTP_DETAIL));
		assertThat(emailVerified(reg.userId)).isFalse();
	}

	@Test
	void unknownEmail_returns400_identicalToWrongCode_noEnumeration() throws Exception {
		Registered reg = register(unique("verify-known"));

		String wrongCodeDetail = mvc.perform(verify(reg.email, wrong(reg.code)))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		String unknownEmailDetail = mvc.perform(verify(unique("verify-unknown"), "000000"))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(unknownEmailDetail)
				.as("unknown email and wrong code must be indistinguishable")
				.isEqualTo(wrongCodeDetail)
				.contains(GENERIC_OTP_DETAIL);
	}

	@Test
	void alreadyVerified_isIdempotent200_andIssuesNoOtp() throws Exception {
		Registered reg = register(unique("verify-idem"));
		mvc.perform(verify(reg.email, reg.code)).andExpect(status().isOk());

		int sendsBefore = mailer.sends.get();
		mvc.perform(verify(reg.email, "000000")).andExpect(status().isOk());

		assertThat(mailer.sends.get()).as("verifying an already-verified account issues no OTP")
				.isEqualTo(sendsBefore);
		assertThat(redis.opsForValue().get(otpKey(reg.userId))).isNull();
	}

	// --- resend ---------------------------------------------------------------------------------

	@Test
	void resend_reissuesFreshCode_andResetsAttemptCounter() throws Exception {
		Registered reg = register(unique("resend-fresh"));
		// Burn two attempts so the counter is non-zero before the resend.
		mvc.perform(verify(reg.email, wrong(reg.code))).andExpect(status().isBadRequest());
		mvc.perform(verify(reg.email, wrong(reg.code))).andExpect(status().isBadRequest());
		assertThat(redis.opsForValue().get(attemptsKey(reg.userId))).isEqualTo("2");

		mvc.perform(resend(reg.email)).andExpect(status().isOk());
		String freshCode = mailer.code;

		assertThat(freshCode).as("resend must issue a new code").isNotEqualTo(reg.code).matches("\\d{6}");
		assertThat(redis.opsForValue().get(otpKey(reg.userId))).isEqualTo(sha256Hex(freshCode));
		assertThat(redis.opsForValue().get(attemptsKey(reg.userId)))
				.as("a fresh code resets the attempt budget").isNull();

		// The fresh code verifies.
		mvc.perform(verify(reg.email, freshCode)).andExpect(status().isOk());
		assertThat(emailVerified(reg.userId)).isTrue();
	}

	@Test
	void resend_withinCooldown_returns429() throws Exception {
		Registered reg = register(unique("resend-cd"));

		mvc.perform(resend(reg.email)).andExpect(status().isOk());
		mvc.perform(resend(reg.email))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.status").value(429));

		Long cooldownTtl = redis.getExpire(cooldownKey(reg.userId), TimeUnit.SECONDS);
		assertThat(cooldownTtl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(60L);
	}

	@Test
	void resend_hourlyCap_returns429_pastFiveSends() throws Exception {
		Registered reg = register(unique("resend-cap"));

		// Clear the cooldown between sends to isolate the 5/hour window cap from the 60 s gate.
		for (int i = 1; i <= 5; i++) {
			redis.delete(cooldownKey(reg.userId));
			mvc.perform(resend(reg.email)).andExpect(status().isOk());
		}
		assertThat(redis.opsForValue().get(sendsKey(reg.userId))).isEqualTo("5");

		redis.delete(cooldownKey(reg.userId));
		mvc.perform(resend(reg.email)).andExpect(status().isTooManyRequests());

		Long windowTtl = redis.getExpire(sendsKey(reg.userId), TimeUnit.SECONDS);
		assertThat(windowTtl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(3600L);
	}

	@Test
	void resend_unknownEmail_returns200_andIssuesNoOtp() throws Exception {
		int sendsBefore = mailer.sends.get();

		mvc.perform(resend(unique("resend-unknown"))).andExpect(status().isOk());

		assertThat(mailer.sends.get()).as("resend to an unknown email must be a silent no-op")
				.isEqualTo(sendsBefore);
	}

	@Test
	void resend_alreadyVerified_returns200_andIssuesNoOtp() throws Exception {
		Registered reg = register(unique("resend-verified"));
		mvc.perform(verify(reg.email, reg.code)).andExpect(status().isOk());

		int sendsBefore = mailer.sends.get();
		mvc.perform(resend(reg.email)).andExpect(status().isOk());

		assertThat(mailer.sends.get()).as("resend to a verified account issues no OTP")
				.isEqualTo(sendsBefore);
		assertThat(redis.opsForValue().get(otpKey(reg.userId))).isNull();
	}

	// --- helpers --------------------------------------------------------------------------------

	private record Registered(UUID userId, String email, String code) {
	}

	private Registered register(String email) throws Exception {
		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Sup3rSecret!\"}"))
				.andExpect(status().isCreated());
		UUID userId = JdbcClient.create(dataSource)
				.sql("SELECT id FROM app_user WHERE email = ?").param(email)
				.query(UUID.class).single();
		return new Registered(userId, email, mailer.code);
	}

	private boolean emailVerified(UUID userId) {
		return Boolean.TRUE.equals(JdbcClient.create(dataSource)
				.sql("SELECT email_verified FROM app_user WHERE id = ?").param(userId)
				.query(Boolean.class).single());
	}

	private static MockHttpServletRequestBuilder verify(String email, String code) {
		return post("/verify").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}");
	}

	private static MockHttpServletRequestBuilder resend(String email) {
		return post("/resend").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}");
	}

	private static String otpKey(UUID userId) {
		return "auth:otp:" + OtpPurpose.VERIFY.slug() + ":" + userId;
	}

	private static String attemptsKey(UUID userId) {
		return otpKey(userId) + ":attempts";
	}

	private static String cooldownKey(UUID userId) {
		return otpKey(userId) + ":cooldown";
	}

	private static String sendsKey(UUID userId) {
		return otpKey(userId) + ":sends";
	}

	/** A different but still 6-digit code, so the request passes validation and reaches the store. */
	private static String wrong(String code) {
		return code.equals("000000") ? "111111" : "000000";
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
