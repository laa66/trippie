package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.UserSettingsData;
import com.laa66.auth.domain.port.in.GetUserSettings;
import com.laa66.auth.domain.port.in.UpdateUserSettings;
import com.laa66.auth.infrastructure.web.SettingsController;

/**
 * Boundary slice for {@code GET /settings} and {@code PUT /settings}: header extraction, payload
 * validation, and the guarantee that the acted-on user id is always the trusted {@code X-User-Id}
 * header (never anything from the body, which carries no user id field at all). The use cases are
 * mocked — persistence lives in {@code SettingsIntegrationTest}.
 */
@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

	private static final UUID USER_ID = UUID.randomUUID();
	private static final String VALID_BODY =
			"{\"defaultContentMode\":\"TEXT\",\"selectedCategories\":[\"museums\",\"viewpoints\"]}";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private GetUserSettings getUserSettings;

	@MockitoBean
	private UpdateUserSettings updateUserSettings;

	@Test
	void get_returnsMockedSettings() throws Exception {
		when(getUserSettings.get(USER_ID)).thenReturn(
				new UserSettingsData(ContentMode.BOTH, List.of("museums", "viewpoints")));

		mvc.perform(get("/settings").header("X-User-Id", USER_ID.toString()))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.contains("\"defaultContentMode\":\"BOTH\"")
						.contains("museums").contains("viewpoints"));
	}

	@Test
	void get_missingUserIdHeader_returns400() throws Exception {
		mvc.perform(get("/settings")).andExpect(status().isBadRequest());

		verifyNoInteractions(getUserSettings);
	}

	@Test
	void get_nonUuidUserIdHeader_returns400() throws Exception {
		mvc.perform(get("/settings").header("X-User-Id", "not-a-uuid"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(getUserSettings);
	}

	@Test
	void put_validRequest_returns200_andCallsUseCaseWithHeaderUserId() throws Exception {
		when(updateUserSettings.update(eq(USER_ID), any(), any())).thenReturn(
				new UserSettingsData(ContentMode.TEXT, List.of("museums", "viewpoints")));

		mvc.perform(put("/settings")
				.header("X-User-Id", USER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(result -> assertThat(result.getResponse().getContentAsString())
						.contains("\"defaultContentMode\":\"TEXT\""));

		verify(updateUserSettings).update(USER_ID, ContentMode.TEXT, List.of("museums", "viewpoints"));
	}

	@Test
	void put_unknownCategorySlug_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(put("/settings")
				.header("X-User-Id", USER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"defaultContentMode\":\"TEXT\",\"selectedCategories\":[\"not-a-real-slug\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

		verifyNoInteractions(updateUserSettings);
	}

	@Test
	void put_unknownContentMode_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(put("/settings")
				.header("X-User-Id", USER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"defaultContentMode\":\"LOUD\",\"selectedCategories\":[\"museums\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

		verifyNoInteractions(updateUserSettings);
	}

	@Test
	void put_missingUserIdHeader_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(put("/settings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(VALID_BODY))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(updateUserSettings);
	}

	@Test
	void put_emptyCategoryList_isValid() throws Exception {
		when(updateUserSettings.update(eq(USER_ID), any(), any())).thenReturn(
				new UserSettingsData(ContentMode.AUDIO, List.of()));

		mvc.perform(put("/settings")
				.header("X-User-Id", USER_ID.toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"defaultContentMode\":\"AUDIO\",\"selectedCategories\":[]}"))
				.andExpect(status().isOk());

		verify(updateUserSettings).update(USER_ID, ContentMode.AUDIO, List.of());
	}
}
