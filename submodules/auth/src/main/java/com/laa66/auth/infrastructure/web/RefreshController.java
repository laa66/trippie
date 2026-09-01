package com.laa66.auth.infrastructure.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.model.InvalidRefreshTokenException;
import com.laa66.auth.domain.model.RefreshResult;
import com.laa66.auth.domain.port.in.RefreshTokens;

/**
 * Refresh endpoint (flow 04). The gateway strips {@code /api/auth}, so this maps {@code /refresh}.
 * The refresh token is read from the httpOnly cookie, never the body. The CSRF double-submit check
 * (readable {@code csrf} cookie must equal the {@code X-CSRF-Token} header) is the first, cheapest
 * gate — it runs before Redis is touched — and a failure is a distinct 403. On success the rotated
 * refresh token and a fresh csrf token are set as cookies and the new access JWT is returned in the
 * body. Every refresh failure (absent/expired/reused) is the uniform 401 from {@link AuthExceptionHandler}.
 */
@RestController
public class RefreshController {

	static final String CSRF_HEADER = "X-CSRF-Token";

	private final RefreshTokens refreshTokens;
	private final AuthCookies cookies;

	public RefreshController(RefreshTokens refreshTokens, AuthCookies cookies) {
		this.refreshTokens = refreshTokens;
		this.cookies = cookies;
	}

	@PostMapping("/refresh")
	public ResponseEntity<RefreshResponse> refresh(
			@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String refreshToken,
			@CookieValue(name = AuthCookies.CSRF_COOKIE, required = false) String csrfCookie,
			@RequestHeader(name = CSRF_HEADER, required = false) String csrfHeader) {

		requireCsrf(csrfCookie, csrfHeader);
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRefreshTokenException();
		}

		RefreshResult result = refreshTokens.refresh(refreshToken);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookies.refreshCookie(result.refreshToken()).toString())
				.header(HttpHeaders.SET_COOKIE, cookies.csrfCookie().toString())
				.body(new RefreshResponse(result.accessToken()));
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
