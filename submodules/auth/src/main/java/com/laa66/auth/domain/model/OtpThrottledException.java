package com.laa66.auth.domain.model;

/**
 * Raised when a resend is refused by the send-throttle (too soon since the last send, or the
 * hourly cap reached). Mapped to a 429 ProblemDetail. The message is generic and carries no
 * account state.
 */
public class OtpThrottledException extends RuntimeException {

	public OtpThrottledException() {
		super("too many verification requests, please try again later");
	}
}
