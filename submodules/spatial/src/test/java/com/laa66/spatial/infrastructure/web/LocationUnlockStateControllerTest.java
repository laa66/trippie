package com.laa66.spatial.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.spatial.domain.port.in.CheckLocationUnlocked;

@WebMvcTest(LocationUnlockStateController.class)
class LocationUnlockStateControllerTest {

	private static final UUID USER = UUID.fromString("77777777-7777-4777-8777-777777777777");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private CheckLocationUnlocked checkLocationUnlocked;

	@Test
	void okResponse_isTheSingleFlag() throws Exception {
		when(checkLocationUnlocked.isUnlocked(USER, "node/42")).thenReturn(true);

		mvc.perform(get("/locations/node/42/unlocked").header("X-User-Id", USER.toString()))
				.andExpect(status().isOk())
				.andExpect(content().json("""
						{"unlocked": true}
						""", JsonCompareMode.STRICT));
	}

	@Test
	void aLockedPoiReportsFalse() throws Exception {
		when(checkLocationUnlocked.isUnlocked(USER, "node/42")).thenReturn(false);

		mvc.perform(get("/locations/node/42/unlocked").header("X-User-Id", USER.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unlocked").value(false));
	}

	/**
	 * Both arguments captured, not stubbed loosely: a handler that forwarded the wrong user, or only
	 * half of the id, would answer about a different row and nothing in the status code would say so.
	 */
	@Test
	void theUserAndTheReassembledIdReachThePort() throws Exception {
		when(checkLocationUnlocked.isUnlocked(any(), anyString())).thenReturn(true);

		mvc.perform(get("/locations/way/7/unlocked").header("X-User-Id", USER.toString()))
				.andExpect(status().isOk());

		ArgumentCaptor<UUID> userId = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> osmId = ArgumentCaptor.forClass(String.class);
		verify(checkLocationUnlocked).isUnlocked(userId.capture(), osmId.capture());
		assertThat(userId.getValue()).isEqualTo(USER);
		assertThat(osmId.getValue()).isEqualTo("way/7");
	}

	/**
	 * The header is required, the M3-04/M3-05 convention: a missing {@code X-User-Id} is a broken
	 * gateway, and answering {@code unlocked: false} for everybody would hide it behind what looks
	 * like a product bug. There is also no honest value to serve about a user nobody named.
	 */
	@Test
	void aRequestWithoutTheUserHeaderIsRejected() throws Exception {
		mvc.perform(get("/locations/node/42/unlocked"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(checkLocationUnlocked);
	}

	/** Bound as a UUID, so a non-UUID header never reaches the port either. */
	@Test
	void aMalformedUserHeaderIsRejected() throws Exception {
		mvc.perform(get("/locations/node/42/unlocked").header("X-User-Id", "not-a-uuid\nX-Evil: 1"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(checkLocationUnlocked);
	}
}
