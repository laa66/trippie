package com.laa66.auth.domain.model;

/**
 * Raised when a registration targets an email that already exists (case-insensitively). The message
 * is deliberately generic and never carries the password. Flow 01 maps this to a 409.
 */
public class EmailAlreadyExistsException extends RuntimeException {

	public EmailAlreadyExistsException() {
		super("email already registered");
	}
}
