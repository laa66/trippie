package com.laa66.auth.domain.model;

/**
 * Raised when correct credentials belong to an account whose email is not yet verified. Mapped to a
 * distinct 403 (its own ProblemDetail type) so the frontend can route to the verify screen. It is
 * thrown only after a successful password check, so it never reveals the existence of an unverified
 * account to someone who does not already know the password.
 */
public class EmailNotVerifiedException extends RuntimeException {

	public EmailNotVerifiedException() {
		super("email verification required");
	}
}
