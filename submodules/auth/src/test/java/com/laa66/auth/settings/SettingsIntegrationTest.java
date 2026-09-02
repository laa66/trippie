package com.laa66.auth.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.auth.domain.model.CategorySlugs;
import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.port.out.UserRepository;
import com.laa66.auth.support.AbstractPostgresIntegrationTest;
import com.laa66.auth.support.EphemeralSigningKeyConfig;

/**
 * End-to-end settings over real Postgres only (Flyway-migrated) — flow 06 never touches Redis or
 * OTP, so this test does not start a Redis container (unlike the other full-context ITs). Exercises
 * controller -> service -> JPA persistence, including the {@code updated_at} bump on write and that
 * one user's write never leaks into another user's row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EphemeralSigningKeyConfig.class)
class SettingsIntegrationTest extends AbstractPostgresIntegrationTest {

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.flyway.enabled", () -> true);
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private DataSource dataSource;

	private JdbcClient jdbc() {
		return JdbcClient.create(dataSource);
	}

	@Test
	void get_returnsSeededSettings() throws Exception {
		UUID userId = seedUser();

		mvc.perform(get("/settings").header("X-User-Id", userId.toString()))
				.andExpect(status().isOk())
				.andExpect(result -> {
					String body = result.getResponse().getContentAsString();
					assertThat(body).contains("\"defaultContentMode\":\"BOTH\"");
					for (String slug : CategorySlugs.ALL) {
						assertThat(body).contains(slug);
					}
				});
	}

	@Test
	void put_updatesSettings_reflectedOnGet_andAdvancesUpdatedAt() throws Exception {
		UUID userId = seedUser();
		Instant before = updatedAt(userId);

		Thread.sleep(5); // Postgres timestamptz precision guard so the bump is provably forward.

		mvc.perform(putSettings(userId, "TEXT", "[\"museums\",\"viewpoints\"]"))
				.andExpect(status().isOk())
				.andExpect(result -> {
					String body = result.getResponse().getContentAsString();
					assertThat(body).contains("\"defaultContentMode\":\"TEXT\"")
							.contains("museums").contains("viewpoints")
							.doesNotContain("public_art");
				});

		mvc.perform(get("/settings").header("X-User-Id", userId.toString()))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.contains("\"defaultContentMode\":\"TEXT\""));

		Map<String, Object> row = jdbc()
				.sql("SELECT default_content_mode, array_to_string(selected_categories, ',') AS cats "
						+ "FROM user_settings WHERE user_id = ?")
				.param(userId)
				.query()
				.singleRow();
		assertThat(row.get("default_content_mode")).isEqualTo("TEXT");
		assertThat(((String) row.get("cats")).split(",")).containsExactlyInAnyOrder("museums", "viewpoints");

		Instant after = updatedAt(userId);
		assertThat(after).isAfter(before);
	}

	@Test
	void put_unknownSlug_returns400_andRowUnchanged() throws Exception {
		UUID userId = seedUser();

		mvc.perform(putSettings(userId, "TEXT", "[\"not-a-real-slug\"]"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

		String mode = jdbc()
				.sql("SELECT default_content_mode FROM user_settings WHERE user_id = ?")
				.param(userId)
				.query(String.class)
				.single();
		assertThat(mode).isEqualTo("BOTH");
	}

	@Test
	void put_nonExistentUserId_returns404_notAFabricated200() throws Exception {
		UUID unknownUserId = UUID.randomUUID(); // no app_user/user_settings row ever seeded

		mvc.perform(putSettings(unknownUserId, "TEXT", "[\"museums\"]"))
				.andExpect(status().isNotFound())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
	}

	@Test
	void put_emptyCategoryList_persistsAsEmptyArray_reflectedOnGet() throws Exception {
		UUID userId = seedUser();

		mvc.perform(putSettings(userId, "AUDIO", "[]"))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.contains("\"defaultContentMode\":\"AUDIO\"")
						.contains("\"selectedCategories\":[]"));

		mvc.perform(get("/settings").header("X-User-Id", userId.toString()))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.contains("\"selectedCategories\":[]"));

		String cats = jdbc()
				.sql("SELECT array_to_string(selected_categories, ',') FROM user_settings WHERE user_id = ?")
				.param(userId)
				.query(String.class)
				.single();
		assertThat(cats).isEmpty();
	}

	@Test
	void writingUserA_neverTouchesUserB() throws Exception {
		UUID userA = seedUser();
		UUID userB = seedUser();

		mvc.perform(putSettings(userA, "AUDIO", "[\"museums\"]")).andExpect(status().isOk());

		mvc.perform(get("/settings").header("X-User-Id", userB.toString()))
				.andExpect(status().isOk())
				.andExpect(result -> {
					String body = result.getResponse().getContentAsString();
					assertThat(body).contains("\"defaultContentMode\":\"BOTH\"");
					for (String slug : CategorySlugs.ALL) {
						assertThat(body).contains(slug);
					}
				});
	}

	private UUID seedUser() {
		return userRepository.create(unique(), "irrelevant-hash", ContentMode.BOTH, CategorySlugs.ALL);
	}

	private Instant updatedAt(UUID userId) {
		Timestamp ts = jdbc()
				.sql("SELECT updated_at FROM user_settings WHERE user_id = ?")
				.param(userId)
				.query(Timestamp.class)
				.single();
		return ts.toInstant();
	}

	private static MockHttpServletRequestBuilder putSettings(UUID userId, String mode, String categoriesJson) {
		return put("/settings")
				.header("X-User-Id", userId.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"defaultContentMode\":\"" + mode + "\",\"selectedCategories\":" + categoriesJson + "}");
	}

	private static String unique() {
		return "settings-" + UUID.randomUUID() + "@example.com";
	}
}
