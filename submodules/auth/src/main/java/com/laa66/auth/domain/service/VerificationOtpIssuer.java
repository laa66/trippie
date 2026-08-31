package com.laa66.auth.domain.service;

import java.time.Duration;
import java.util.UUID;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.domain.port.out.OtpStore;

/**
 * Single source of truth for issuing a VERIFY OTP: generate, store (hashed, 10-min TTL, attempt
 * counter reset), and hand the raw code to the mailer. Shared by sign-up (M2-03) and resend (M2-04)
 * so the code shape, TTL, and store/mail sequence exist in exactly one place.
 */
public class VerificationOtpIssuer {

	static final Duration TTL = Duration.ofMinutes(10);

	private final OtpGenerator generator;
	private final OtpStore otpStore;
	private final OtpMailer otpMailer;

	public VerificationOtpIssuer(OtpGenerator generator, OtpStore otpStore, OtpMailer otpMailer) {
		this.generator = generator;
		this.otpStore = otpStore;
		this.otpMailer = otpMailer;
	}

	public void issue(UUID userId, String email) {
		String code = generator.generate();
		otpStore.store(OtpPurpose.VERIFY, userId, code, TTL);
		otpMailer.send(OtpPurpose.VERIFY, email, code);
	}
}
