package com.laa66.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.EphemeralSigningKeyConfig;
import com.laa66.auth.support.RedisTestContainer;
import org.springframework.context.annotation.Import;

/**
 * End-to-end registration over real Postgres + Redis (Flyway-migrated). Exercises the whole slice:
 * controller -> service -> JPA persistence -> BCrypt hasher -> Redis OTP store -> mailer port. A
 * capturing mailer stands in for the dev-stub so the issued code can be checked against what landed
 * in Redis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class RegistrationIntegrationTest extends AbstractPostgresIntegrationTest {

	private static final PasswordEncoder ENCODER = PasswordEncoderFactories.createDelegatingPasswordEncoder();

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

		volatile OtpPurpose purpose;
		volatile String recipient;
		volatile String code;

		@Override
		public void send(OtpPurpose purpose, String recipient, String code) {
			this.purpose = purpose;
			this.recipient = recipient;
			this.code = code;
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

	private JdbcClient jdbc() {
		return JdbcClient.create(dataSource);
	}

	@Test
	void newEmail_persistsUnverifiedUserWithBcryptHash_andSeedsSettings() throws Exception {
		String email = unique("bcrypt");
		String password = "Sup3rSecret!";

		mvc.perform(register(email, password)).andExpect(status().isCreated());

		Map<String, Object> user = jdbc()
				.sql("SELECT id, email_verified, password_hash FROM app_user WHERE email = ?")
				.param(email)
				.query()
				.singleRow();

		assertThat(user.get("email_verified")).isEqualTo(false);

		String hash = (String) user.get("password_hash");
		assertThat(hash).startsWith("{bcrypt}$2").contains("$12$"); // {bcrypt} prefix + strength 12
		assertThat(hash).doesNotContain(password);
		assertThat(ENCODER.matches(password, hash)).isTrue();

		UUID userId = (UUID) user.get("id");
		String mode = jdbc()
				.sql("SELECT default_content_mode FROM user_settings WHERE user_id = ?")
				.param(userId)
				.query(String.class)
				.single();
		assertThat(mode).isEqualTo("BOTH");

		String categories = jdbc()
				.sql("SELECT array_to_string(selected_categories, ',') FROM user_settings WHERE user_id = ?")
				.param(userId)
				.query(String.class)
				.single();
		assertThat(categories.split(",")).containsExactlyInAnyOrder(
				"public_art", "monuments", "heritage", "sacred",
				"museums", "viewpoints", "architecture", "attractions");
	}

	@Test
	void newEmail_issuesSixDigitOtp_hashedIntoRedisWithTenMinuteTtl() throws Exception {
		String email = unique("otp");

		mvc.perform(register(email, "Sup3rSecret!")).andExpect(status().isCreated());

		UUID userId = jdbc()
				.sql("SELECT id FROM app_user WHERE email = ?")
				.param(email)
				.query(UUID.class)
				.single();

		assertThat(mailer.purpose).isEqualTo(OtpPurpose.VERIFY);
		assertThat(mailer.recipient).isEqualTo(email);
		assertThat(mailer.code).matches("\\d{6}");

		// Assert the frozen key + hashing contract independently of the production code.
		String key = "auth:otp:verify:" + userId;
		String stored = redis.opsForValue().get(key);
		assertThat(stored)
				.as("raw code must never rest in Redis")
				.isNotNull()
				.isNotEqualTo(mailer.code)
				.isEqualTo(sha256Hex(mailer.code));

		Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
		assertThat(ttl).isNotNull().isGreaterThan(540L).isLessThanOrEqualTo(600L);
	}

	@Test
	void duplicateEmailCaseInsensitive_returns409ProblemDetail_andDoesNotDoubleInsert() throws Exception {
		String email = unique("Dup");

		mvc.perform(register(email, "Sup3rSecret!")).andExpect(status().isCreated());

		mvc.perform(register(email.toUpperCase(), "An0therPass!"))
				.andExpect(status().isConflict())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

		Long count = jdbc()
				.sql("SELECT count(*) FROM app_user WHERE email = ?")
				.param(email)
				.query(Long.class)
				.single();
		assertThat(count).isEqualTo(1L);
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(
			String email, String password) {
		return post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	/** Unique per test so a shared container across the class needs no truncation between methods. */
	private static String unique(String tag) {
		return tag + "-" + UUID.randomUUID() + "@example.com";
	}

	private static String sha256Hex(String value) {
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
