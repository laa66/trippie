package com.laa66.auth.domain.model;

/**
 * Raised for both an unknown email and a wrong password, so the two are indistinguishable to the
 * caller (identical 401, same body). No account enumeration.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("invalid email or password");
	}
}
