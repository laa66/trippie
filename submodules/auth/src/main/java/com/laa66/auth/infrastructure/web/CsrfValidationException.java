package com.laa66.auth.infrastructure.web;

/**
 * Raised when the double-submit CSRF check fails on {@code /refresh} (or later {@code /logout}): the
 * {@code X-CSRF-Token} header is absent or does not equal the readable {@code csrf} cookie. Purely a
 * transport concern, so it lives in the web adapter, not the domain. Mapped to a distinct 403.
 */
class CsrfValidationException extends RuntimeException {

	CsrfValidationException() {
		super("CSRF token missing or mismatched");
	}
}
