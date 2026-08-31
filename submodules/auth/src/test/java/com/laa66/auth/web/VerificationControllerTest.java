package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.laa66.auth.domain.model.InvalidOtpException;
import com.laa66.auth.domain.model.OtpThrottledException;
import com.laa66.auth.domain.port.in.ResendVerification;
import com.laa66.auth.domain.port.in.VerifyEmail;
import com.laa66.auth.infrastructure.web.VerificationController;

/**
 * Boundary slice for {@code POST /verify} and {@code POST /resend}: payload validation and the
 * status/ProblemDetail mapping of the two domain failures. The use cases are mocked — the OTP,
 * throttle, and persistence behaviour live in {@code EmailVerificationIntegrationTest}.
 */
@WebMvcTest(VerificationController.class)
class VerificationControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private VerifyEmail verifyEmail;

	@MockitoBean
	private ResendVerification resendVerification;

	@Test
	void verify_validRequest_returns200_andDelegatesEmailAndCode() throws Exception {
		mvc.perform(post("/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"User@Example.com\",\"code\":\"123456\"}"))
				.andExpect(status().isOk());

		verify(verifyEmail).verify("User@Example.com", "123456");
	}

	@Test
	void verify_malformedEmail_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"code\":\"123456\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

		verifyNoInteractions(verifyEmail);
	}

	@Test
	void verify_nonSixDigitCode_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"code\":\"12ab\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(verifyEmail);
	}

	@Test
	void verify_invalidOtp_returns400ProblemDetail() throws Exception {
		doThrow(new InvalidOtpException()).when(verifyEmail).verify(any(), any());

		mvc.perform(post("/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"code\":\"000000\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
	}

	@Test
	void resend_validRequest_returns200_andDelegatesEmail() throws Exception {
		mvc.perform(post("/resend")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"User@Example.com\"}"))
				.andExpect(status().isOk());

		verify(resendVerification).resend("User@Example.com");
	}

	@Test
	void resend_malformedEmail_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/resend")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"nope\"}"))
				.andExpect(status().isBadRequest());

		verify(resendVerification, never()).resend(any());
	}

	@Test
	void resend_throttled_returns429ProblemDetail() throws Exception {
		doThrow(new OtpThrottledException()).when(resendVerification).resend(eq("user@example.com"));

		mvc.perform(post("/resend")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
	}
}
