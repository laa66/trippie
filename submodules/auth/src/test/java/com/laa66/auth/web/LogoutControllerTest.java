package com.laa66.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * Boundary slice for {@code POST /logout}: the CSRF double-submit gate (checked BEFORE anything else and
 * on every variant), the 204 with both cleared cookies, the uniform 401 for an unusable access token,
 * and — since M2-19 — the OPTIONAL bearer. Both the use case and the token verifier are mocked; the
 * real revocation + denylist writes and the TTL≈remaining-life proof live in
 * {@code LogoutIntegrationTest}, and the bearer-less revocation is proven end to end in
 * {@code BearerlessLogoutIntegrationTest}.
 *
 * <p>The crucial distinction pinned here is PRESENCE vs VALIDITY: no {@code Authorization} header is the
 * replay path (204, cookie-only, no denylist write), while a header that is present but unusable is a
 * bad credential (401, nothing revoked). Per M2-19 criterion (20) none of this is sufficient on its own
 * — the gateway sits on the real request path, so {@code GatewaySecurityIntegrationTest} must show the
 * tokenless call actually reaches auth.
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

	/**
	 * M2-19 criterion (18) — REWRITTEN from the former
	 * {@code missingBearer_withValidCsrf_returns401_andNeverCallsUseCase}, which asserted the behaviour
	 * this task deliberately reverses. The inversion is sanctioned by the 2026-10-03 ruling recorded in
	 * PLAN.md (M2 frozen block + M2-19 criteria 16-21), NOT a unilateral loosening: criterion (2)
	 * shields the M2-08 integration tests, and deliberately does not shield this slice.
	 *
	 * <p>Criterion (1): no bearer + valid CSRF -> 204, the family IS revoked, and the denylist is never
	 * written because the use case is handed a {@code null} jti. The verifier is never reached at all —
	 * there is nothing to verify, so the cookie-only path cannot be a disguised verification bypass.
	 */
	@Test
	void missingBearer_withValidCsrf_returns204_revokesFamily_andNeverDenylists() throws Exception {
		mvc.perform(post("/logout")
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isNoContent());

		// The family is revoked; the null jti is what suppresses the denylist write downstream.
		verify(logout).logout(eq("raw-refresh"), isNull(), isNull());
		verifyNoInteractions(accessTokenVerifier);
	}

	/** Criterion (3): neither bearer nor refresh cookie — still 204, still nothing to verify. */
	@Test
	void neitherBearerNorRefreshCookie_returns204_idempotently() throws Exception {
		mvc.perform(post("/logout")
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isNoContent());

		verify(logout).logout(isNull(), isNull(), isNull());
		verifyNoInteractions(accessTokenVerifier);
	}

	/**
	 * Criterion (4): CSRF is mandatory on the bearer-LESS variant too. Without this the replay path
	 * would be an unauthenticated, cross-site-triggerable revocation endpoint.
	 */
	@Test
	void missingBearer_withoutCsrf_returns403_andRevokesNothing() throws Exception {
		mvc.perform(post("/logout")
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("urn:trippie:auth:csrf"));

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

	/**
	 * Criterion (5), the load-bearing one: a bearer that is PRESENT but unusable must NOT be silently
	 * downgraded to the cookie-only path. If it were, a forged/expired token would buy a 204 while
	 * skipping the {@code jti} denylist write — so the attacker's still-live access token would keep
	 * working at the gateway. 401 and NO revocation at all is the only acceptable answer.
	 *
	 * <p>Mutation that must turn this red: changing the controller's branch from "header absent" to
	 * "no usable token" (e.g. catching {@link InvalidAccessTokenException} and falling through to the
	 * cookie-only call) — then this returns 204 and {@code logout} is invoked.
	 */
	@Test
	void presentButExpiredBearer_returns401_andIsNotDowngradedToTheCookieOnlyPath() throws Exception {
		when(accessTokenVerifier.verify("header.payload.sig")).thenThrow(new InvalidAccessTokenException());

		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, BEARER)
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isUnauthorized());

		// Not merely "no denylist write" — the family is not revoked either, so a bad token buys nothing.
		verifyNoInteractions(logout);
	}

	/**
	 * Criterion (5) again, for a header that is present but not a Bearer at all. "Present and
	 * unparseable" is a bad credential, not a missing one, so it must 401 rather than fall through to
	 * the bearer-less branch.
	 */
	@Test
	void presentNonBearerAuthorizationHeader_returns401_andNeverReachesTheCookieOnlyPath() throws Exception {
		mvc.perform(post("/logout")
				.header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
				.cookie(new Cookie("refresh_token", "raw-refresh"))
				.cookie(new Cookie("csrf", CSRF))
				.header("X-CSRF-Token", CSRF))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(logout);
	}

	/**
	 * LOW-3 (Light, M2-19 review). The branch keys off {@code authorization == null}, so a header that
	 * is PRESENT but blank falls to {@code bearerToken()} and 401s rather than being treated as absent
	 * and downgraded to the cookie-only replay path. That is the safer of the two readings and it is
	 * the decision recorded here, because widening the condition to {@code || isBlank()} is a mutation
	 * that otherwise survives the whole suite. It is reachable in production: an empty
	 * {@code Authorization} header passes the gateway and arrives at auth (measured), so the choice
	 * has to be made on this side.
	 *
	 * <p>Treating blank-as-absent would also be a real, if narrow, downgrade: it would let a caller
	 * pick the no-denylist-write path by sending an empty header instead of no header, i.e. let
	 * client-controlled input choose the branch — the same objection that got "admit /logout only when
	 * no Authorization header is present" rejected at the gateway.
	 */
	@Test
	void blankAuthorizationHeader_returns401_andIsNotTreatedAsAbsent() throws Exception {
		for (String blank : List.of("", " ")) {
			mvc.perform(post("/logout")
					.header(HttpHeaders.AUTHORIZATION, blank)
					.cookie(new Cookie("refresh_token", "raw-refresh"))
					.cookie(new Cookie("csrf", CSRF))
					.header("X-CSRF-Token", CSRF))
					.andExpect(status().isUnauthorized());
		}

		verifyNoInteractions(logout);
	}
}
