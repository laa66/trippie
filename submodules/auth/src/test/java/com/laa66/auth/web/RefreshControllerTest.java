package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.util.List;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.laa66.auth.domain.model.InvalidRefreshTokenException;
import com.laa66.auth.domain.model.RefreshResult;
import com.laa66.auth.domain.port.in.RefreshTokens;
import com.laa66.auth.infrastructure.web.AuthCookies;
import com.laa66.auth.infrastructure.web.RefreshController;

import jakarta.servlet.http.Cookie;

/**
 * Boundary slice for {@code POST /refresh}: the CSRF double-submit gate (checked BEFORE the use case
 * is ever reached), the cookie-read of the refresh token, the two rotated Set-Cookie headers, and the
 * uniform 401 for an unusable refresh token. The use case is mocked — rotation/reuse logic lives in
 * {@code RefreshServiceTest} + {@code RefreshIntegrationTest}.
 */
@WebMvcTest(RefreshController.class)
@Import({ AuthCookies.class, RefreshControllerTest.SecureRandomConfig.class })
class RefreshControllerTest {

	private static final String CSRF = "csrf-token-value";

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
	private RefreshTokens refreshTokens;

	private static String cookieHeader(MvcResult result, String namePrefix) {
		List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return cookies.stream().filter(c -> c.startsWith(namePrefix)).findFirst().orElseThrow();
	}

	@Test
	void validCsrfAndRefreshCookie_returns200_withAccessTokenAndRotatedCookies() throws Exception {
		when(refreshTokens.refresh("old-raw")).thenReturn(new RefreshResult("new.access.jwt", "new-raw"));

		MvcResult result = mvc.perform(post("/refresh")
				.cookie(new Cookie("refresh_token", "old-raw"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("new.access.jwt"))
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andReturn();

		String refresh = cookieHeader(result, "refresh_token=");
		assertThat(refresh)
				.contains("refresh_token=new-raw")
				.contains("HttpOnly")
				.contains("SameSite=Strict")
				.contains("Path=/api/auth");

		String csrf = cookieHeader(result, "csrf=");
		assertThat(csrf).contains("SameSite=Strict").contains("Path=/").doesNotContain("Path=/api/auth").doesNotContain("HttpOnly");
	}

	@Test
	void missingCsrfHeader_returns403_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/refresh")
				.cookie(new Cookie("refresh_token", "old-raw"))
				.cookie(new Cookie("csrf", CSRF)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));

		verifyNoInteractions(refreshTokens);
	}

	@Test
	void csrfHeaderMismatch_returns403_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/refresh")
				.cookie(new Cookie("refresh_token", "old-raw"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", "different-value"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));

		verifyNoInteractions(refreshTokens);
	}

	@Test
	void missingCsrfCookie_returns403_andNeverCallsUseCase() throws Exception {
		mvc.perform(post("/refresh")
				.cookie(new Cookie("refresh_token", "old-raw"))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));

		verifyNoInteractions(refreshTokens);
	}

	@Test
	void missingRefreshCookie_withValidCsrf_returns401_andNeverCallsUseCase() throws Exception {
		// CSRF passes (cookie == header), so this proves the SEPARATE absent-refresh gate: 401, not 403.
		mvc.perform(post("/refresh")
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(refreshTokens);
	}

	@Test
	void unusableRefreshToken_returns401_uniform() throws Exception {
		when(refreshTokens.refresh(any())).thenThrow(new InvalidRefreshTokenException());

		MvcResult result = validRefresh()
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("invalid or expired session"))
				.andReturn();

		// No enumeration/oracle and no rotated cookies handed back on failure.
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		assertThat(result.getResponse().getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
	}

	private org.springframework.test.web.servlet.ResultActions validRefresh() throws Exception {
		MockHttpServletRequestBuilder request = post("/refresh")
				.cookie(new Cookie("refresh_token", "old-raw"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF);
		return mvc.perform(request);
	}
}
