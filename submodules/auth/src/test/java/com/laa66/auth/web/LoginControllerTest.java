package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.laa66.auth.domain.model.EmailNotVerifiedException;
import com.laa66.auth.domain.model.InvalidCredentialsException;
import com.laa66.auth.domain.model.LoginResult;
import com.laa66.auth.domain.port.in.LoginUser;
import com.laa66.auth.infrastructure.web.AuthCookies;
import com.laa66.auth.infrastructure.web.LoginController;

/**
 * Boundary slice for {@code POST /login}: response body shape, the two Set-Cookie headers with the
 * correct flags (refresh httpOnly + Secure + SameSite=Strict + Path=/api/auth; csrf readable), the
 * distinct 403 for an unverified account, and the uniform 401. The use case is mocked — the auth
 * logic lives in {@code LoginServiceTest} + {@code LoginIntegrationTest}.
 */
@WebMvcTest(LoginController.class)
@Import({ AuthCookies.class, LoginControllerTest.SecureRandomConfig.class })
class LoginControllerTest {

	@TestConfiguration
	static class SecureRandomConfig {
		@Bean
		SecureRandom secureRandom() {
			return new SecureRandom();
		}
	}

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private LoginUser loginUser;

	private static String cookieHeader(MvcResult result, String namePrefix) {
		List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return cookies.stream().filter(c -> c.startsWith(namePrefix)).findFirst().orElseThrow();
	}

	@Test
	void validCredentials_returns200_withAccessTokenBodyAndBothCookies() throws Exception {
		when(loginUser.login("user@example.com", "pw"))
				.thenReturn(new LoginResult("access.jwt", "raw-refresh"));

		MvcResult result = mvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"password\":\"pw\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("access.jwt"))
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andReturn();

		String refresh = cookieHeader(result, "refresh_token=");
		assertThat(refresh)
				.contains("refresh_token=raw-refresh")
				.contains("HttpOnly")
				.contains("Secure")
				.contains("SameSite=Strict")
				.contains("Path=/api/auth")
				.contains("Max-Age=2592000"); // 30 days

		String csrf = cookieHeader(result, "csrf=");
		assertThat(csrf)
				.contains("SameSite=Strict")
				.contains("Path=/") // origin-root so the SPA at / can read it via document.cookie
				.doesNotContain("Path=/api/auth") // must NOT share the refresh cookie's narrow path
				.doesNotContain("HttpOnly"); // readable so the client can echo it in X-CSRF-Token
	}

	@Test
	void unverifiedAccount_returns403_withDistinctProblemType() throws Exception {
		when(loginUser.login(any(), any())).thenThrow(new EmailNotVerifiedException());

		performLoginExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:email-not-verified"))
				.andExpect(jsonPath("$.detail").value("email verification required"));
	}

	@Test
	void invalidCredentials_returns401ProblemDetail() throws Exception {
		when(loginUser.login(any(), any())).thenThrow(new InvalidCredentialsException());

		performLoginExpect(status().isUnauthorized())
				.andExpect(result -> assertThat(result.getResponse().getContentType())
						.contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
				.andExpect(jsonPath("$.detail").value("invalid email or password"));
	}

	@Test
	void malformedEmail_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"not-an-email\",\"password\":\"pw\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(loginUser);
	}

	@Test
	void blankPassword_returns400_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"password\":\"\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(loginUser);
	}

	private org.springframework.test.web.servlet.ResultActions performLoginExpect(
			org.springframework.test.web.servlet.ResultMatcher status) throws Exception {
		return mvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"user@example.com\",\"password\":\"pw\"}"))
				.andExpect(status);
	}
}
