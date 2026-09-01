package com.laa66.auth.infrastructure.web;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the auth transport cookies, scoped to {@code /api/auth} so they never ride on ordinary
 * requests. The refresh cookie is httpOnly (JS cannot read it); the csrf cookie is deliberately
 * readable so the frontend can echo it in {@code X-CSRF-Token} for the double-submit check enforced
 * on /refresh + /logout (M2-07/08). {@code Secure} is on by default and overridable for local http.
 */
@Component
public class AuthCookies {

	static final String REFRESH_COOKIE = "refresh_token";
	static final String CSRF_COOKIE = "csrf";
	static final String PATH = "/api/auth";
	private static final Duration MAX_AGE = Duration.ofDays(30);
	private static final int CSRF_BYTES = 32;

	private final boolean secure;
	private final SecureRandom random;

	AuthCookies(@Value("${auth.cookies.secure:true}") boolean secure, SecureRandom random) {
		this.secure = secure;
		this.random = random;
	}

	ResponseCookie refreshCookie(String rawToken) {
		return ResponseCookie.from(REFRESH_COOKIE, rawToken)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path(PATH)
				.maxAge(MAX_AGE)
				.build();
	}

	ResponseCookie csrfCookie() {
		byte[] value = new byte[CSRF_BYTES];
		random.nextBytes(value);
		return ResponseCookie.from(CSRF_COOKIE, Base64.getUrlEncoder().withoutPadding().encodeToString(value))
				.httpOnly(false)
				.secure(secure)
				.sameSite("Strict")
				.path(PATH)
				.maxAge(MAX_AGE)
				.build();
	}

	/**
	 * Expiring counterparts for logout (M2-08). A browser only drops a cookie when the clearing
	 * Set-Cookie repeats its Path and attributes exactly, so these mirror {@link #refreshCookie} /
	 * {@link #csrfCookie} attribute-for-attribute and differ only in an empty value + {@code Max-Age=0}.
	 */
	ResponseCookie clearRefreshCookie() {
		return ResponseCookie.from(REFRESH_COOKIE, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite("Strict")
				.path(PATH)
				.maxAge(0)
				.build();
	}

	ResponseCookie clearCsrfCookie() {
		return ResponseCookie.from(CSRF_COOKIE, "")
				.httpOnly(false)
				.secure(secure)
				.sameSite("Strict")
				.path(PATH)
				.maxAge(0)
				.build();
	}
}
