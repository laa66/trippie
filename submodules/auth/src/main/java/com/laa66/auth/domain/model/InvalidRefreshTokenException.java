package com.laa66.auth.domain.model;

/**
 * Raised for every non-rotatable refresh presentation — absent, expired, unknown, or an
 * already-rotated (reused) token — so the caller cannot tell them apart: one identical 401, same
 * body, no oracle. A reuse additionally revokes the whole family, but that side effect happens in
 * the store and is invisible on the wire.
 */
public class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException() {
		super("invalid or expired session");
	}
}
