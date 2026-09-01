package com.laa66.auth.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.model.InvalidAccessTokenException;
import com.laa66.auth.domain.port.in.Logout;
import com.laa66.auth.infrastructure.security.AccessTokenVerifier;
import com.laa66.auth.infrastructure.security.AccessTokenVerifier.VerifiedAccessToken;

/**
 * Logout endpoint (flow 05). The gateway strips {@code /api/auth}, so this maps {@code /logout}.
 * Gate order mirrors {@code /refresh}: the CSRF double-submit ({@code X-CSRF-Token} == {@code csrf}
 * cookie) runs first and cheapest, then the bearer access token is verified so its {@code jti}/{@code
 * exp} can drive the denylist. On success the refresh family is revoked, the {@code jti} is denylisted
 * for its remaining life, both transport cookies are cleared ({@code Max-Age=0}, same attributes as
 * when set), and the response is 204. Idempotent — a repeat with the same still-valid token is 204.
 */
@RestController
public class LogoutController {

	private static final String BEARER_PREFIX = "Bearer ";

	private final Logout logout;
	private final AccessTokenVerifier accessTokenVerifier;
	private final AuthCookies cookies;

	public LogoutController(Logout logout, AccessTokenVerifier accessTokenVerifier, AuthCookies cookies) {
		this.logout = logout;
		this.accessTokenVerifier = accessTokenVerifier;
		this.cookies = cookies;
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refreshToken,
			@CookieValue(name = AuthCookies.CSRF_COOKIE, required = false) String csrfCookie,
			@RequestHeader(name = RefreshController.CSRF_HEADER, required = false) String csrfHeader) {

		requireCsrf(csrfCookie, csrfHeader);
		VerifiedAccessToken token = accessTokenVerifier.verify(bearerToken(authorization));

		logout.logout(refreshToken, token.jti(), token.remainingLife());

		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clearRefreshCookie().toString())
				.header(HttpHeaders.SET_COOKIE, cookies.clearCsrfCookie().toString())
				.build();
	}

	private static String bearerToken(String authorization) {
		if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
			throw new InvalidAccessTokenException();
		}
		return authorization.substring(BEARER_PREFIX.length());
	}

	/** Double-submit: header must be present and constant-time-equal to the cookie. */
	private static void requireCsrf(String cookie, String header) {
		if (cookie == null || cookie.isBlank() || header == null
				|| !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8),
						header.getBytes(StandardCharsets.UTF_8))) {
			throw new CsrfValidationException();
		}
	}
}
