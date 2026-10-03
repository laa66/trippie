package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.laa66.auth.domain.model.InvalidAccessTokenException;
import com.laa66.auth.domain.port.in.Logout;
import com.laa66.auth.infrastructure.security.AccessTokenVerifier;
import com.laa66.auth.infrastructure.security.AccessTokenVerifier.VerifiedAccessToken;
import com.laa66.auth.infrastructure.web.AuthCookies;
import com.laa66.auth.infrastructure.web.LogoutController;

import jakarta.servlet.http.Cookie;

/**
 * Boundary slice for {@code POST /logout}: the CSRF double-submit gate (checked BEFORE anything else),
 * the bearer-token requirement, the 204 with both cleared cookies, and the uniform 401 for an
 * unusable access token. Both the use case and the token verifier are mocked — the real revocation +
 * denylist writes and the TTL≈remaining-life proof live in {@code LogoutIntegrationTest}.
 */
@WebMvcTest(LogoutController.class)
@Import({ AuthCookies.class, LogoutControllerTest.SecureRandomConfig.class })
class LogoutControllerTest {

	private static final String CSRF = "csrf-token-value";
	private static final String BEARER = "Bearer header.payload.sig";

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
	private Logout logout;

	@MockitoBean
	private AccessTokenVerifier accessTokenVerifier;

	private static String cookieHeader(MvcResult result, String namePrefix) {
		List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		return cookies.stream().filter(c -> c.startsWith(namePrefix)).findFirst().orElseThrow();
	}

	@Test
	void authenticatedLogout_returns204_revokes_andClearsBothCookies() throws Exception {
		when(accessTokenVerifier.verify("header.payload.sig"))
				.thenReturn(new VerifiedAccessToken("jti-123", Duration.ofMinutes(12)));

		MvcResult result = mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, BEARER)
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isNoContent())
				.andReturn();

		verify(logout).logout(eq("raw-refresh"), eq("jti-123"), eq(Duration.ofMinutes(12)));

		String refresh = cookieHeader(result, "refresh_token=");
		assertThat(refresh)
				.contains("refresh_token=;")
				.contains("Max-Age=0")
				.contains("HttpOnly")
				.contains("SameSite=Strict")
				.contains("Path=/api/auth");

		String csrf = cookieHeader(result, "csrf=");
		assertThat(csrf)
				.contains("Max-Age=0")
				.contains("SameSite=Strict")
				.contains("Path=/") // clear must repeat the origin-root Path or the browser keeps the cookie
				.doesNotContain("Path=/api/auth")
				.doesNotContain("HttpOnly");
	}

	@Test
	void missingCsrfHeader_returns403_beforeTouchingTokenOrUseCase() throws Exception {
		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, BEARER)
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));

		verifyNoInteractions(accessTokenVerifier, logout);
	}

	@Test
	void csrfHeaderMismatch_returns403_beforeTouchingTokenOrUseCase() throws Exception {
		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, BEARER)
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", "different-value"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));

		verifyNoInteractions(accessTokenVerifier, logout);
	}

	@Test
	void missingBearer_withValidCsrf_returns401_andNeverCallsUseCase() throws Exception {
		// CSRF passes, so this proves the SEPARATE absent-token gate: 401, and the verifier is never
		// even reached (the missing Authorization header is rejected in the controller).
		mvc.perform(post("/logout")
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(accessTokenVerifier, logout);
	}

	@Test
	void unverifiableToken_returns401_andNeverCallsUseCase() throws Exception {
		when(accessTokenVerifier.verify(any())).thenThrow(new InvalidAccessTokenException());

		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, BEARER)
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(logout);
	}
}
