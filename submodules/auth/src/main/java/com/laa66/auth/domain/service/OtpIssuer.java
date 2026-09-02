package com.laa66.auth.domain.service;

import java.time.Duration;
import java.util.UUID;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.domain.port.out.OtpStore;

/**
 * Single source of truth for issuing an OTP of any {@link OtpPurpose}: generate, store (hashed,
 * 10-min TTL, attempt counter reset), and hand the raw code to the mailer. Shared by sign-up and
 * resend (VERIFY, M2-03/M2-04) and by forgot-password (RESET, M2-09) so the code shape, TTL, and
 * store/mail sequence exist in exactly one place — the purpose only selects the Redis key namespace
 * and the mailer template.
 */
public class OtpIssuer {

	static final Duration TTL = Duration.ofMinutes(10);

	private final OtpGenerator generator;
	private final OtpStore otpStore;
	private final OtpMailer otpMailer;

	public OtpIssuer(OtpGenerator generator, OtpStore otpStore, OtpMailer otpMailer) {
		this.generator = generator;
		this.otpStore = otpStore;
		this.otpMailer = otpMailer;
	}

	public void issue(OtpPurpose purpose, UUID userId, String email) {
		String code = generator.generate();
		otpStore.store(purpose, userId, code, TTL);
		otpMailer.send(purpose, email, code);
	}
}
