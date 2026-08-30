package com.laa66.auth.domain.model;

/**
 * The two OTP flows, each with its own Redis key namespace ({@code auth:otp:{slug}:{userId}}).
 * Registration issues a {@link #VERIFY} code; {@link #RESET} is the password-recovery variant the
 * same store/mailer machinery serves in M2-09.
 */
public enum OtpPurpose {
	VERIFY("verify"),
	RESET("reset");

	private final String slug;

	OtpPurpose(String slug) {
		this.slug = slug;
	}

	public String slug() {
		return slug;
	}
}
