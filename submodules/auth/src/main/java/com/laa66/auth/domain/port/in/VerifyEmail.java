package com.laa66.auth.domain.port.in;

/**
 * Inbound port for the email-verification use case (flow NEW). Resolves the account by email,
 * checks the submitted OTP, and flips {@code email_verified}. Throws
 * {@link com.laa66.auth.domain.model.InvalidOtpException} on any non-success so the boundary maps a
 * single generic 400; an already-verified account is a silent idempotent no-op.
 */
public interface VerifyEmail {

	void verify(String email, String code);
}
