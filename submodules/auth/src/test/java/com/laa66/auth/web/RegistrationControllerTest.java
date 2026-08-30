package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.RegistrationCommand;
import com.laa66.auth.domain.port.in.RegisterUser;
import com.laa66.auth.infrastructure.web.RegistrationController;

/**
 * Boundary slice for {@code POST /register}: validation, status mapping, and the guarantee that the
 * submitted password never leaves through the response. The use case is mocked — persistence, OTP
 * and hashing are covered by the integration test.
 */
@WebMvcTest(RegistrationController.class)
class RegistrationControllerTest {

	private static final String STRONG_PASSWORD = "Sup3rSecret!";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RegisterUser registerUser;

	@Test
	void validRequest_returns201_andPassesEmailAndRawPassword() throws Exception {
		when(registerUser.register(any())).thenReturn(UUID.randomUUID());

		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"New@Example.com\",\"password\":\"" + STRONG_PASSWORD + "\"}"))
				.andExpect(status().isCreated());

		ArgumentCaptor<RegistrationCommand> command = ArgumentCaptor.forClass(RegistrationCommand.class);
		verify(registerUser).register(command.capture());
		assertThat(command.getValue().email()).isEqualTo("New@Example.com");
		assertThat(command.getValue().rawPassword()).isEqualTo(STRONG_PASSWORD);
	}

	@Test
	void duplicateEmail_returns409ProblemDetail() throws Exception {
		when(registerUser.register(any())).thenThrow(new EmailAlreadyExistsException());

		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"dupe@example.com\",\"password\":\"" + STRONG_PASSWORD + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
	}

	@Test
	void malformedEmail_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"password\":\"" + STRONG_PASSWORD + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

		verifyNoInteractions(registerUser);
	}

	@Test
	void emptyPassword_returns400() throws Exception {
		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest());

		verify(registerUser, never()).register(any());
	}

	@Test
	void passwordOver72Utf8Bytes_returns400_evenWhenUnder72Chars() throws Exception {
		// 72 chars of U+0142 = 144 UTF-8 bytes: passes @Size(min=8) and would pass a naive
		// @Size(max=72) on char count, but exceeds BCrypt's 72-byte limit -> must be rejected.
		String password = "ł".repeat(72);
		String json = "{\"email\":\"user@example.com\",\"password\":\"" + password + "\"}";

		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.andExpect(status().isBadRequest());

		verify(registerUser, never()).register(any());
	}

	@Test
	void weakShortPassword_returns400() throws Exception {
		mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"password\":\"short\"}"))
				.andExpect(status().isBadRequest());

		verify(registerUser, never()).register(any());
	}

	@Test
	void validationError_neverEchoesTheSubmittedPassword() throws Exception {
		String secret = "PlaintextLeak42";

		MvcResult result = mvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"password\":\"" + secret + "\"}"))
				.andExpect(status().isBadRequest())
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(secret);
	}
}
