package com.laa66.auth.domain.model;

/**
 * Raised whenever {@code /verify} cannot consume a valid code — wrong code, expired/absent code,
 * attempt cap exceeded, or an email that resolves to no account. The message is deliberately
 * uniform so none of these cases becomes an enumeration oracle. Mapped to a 400 ProblemDetail.
 */
public class InvalidOtpException extends RuntimeException {

	public InvalidOtpException() {
		super("invalid or expired verification code");
	}
}
