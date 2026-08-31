package com.laa66.auth.domain.service;

import java.security.SecureRandom;

/**
 * Generates a 6-digit OTP (10^6 space — 4 digits would be brute-forceable, frozen decision) from a
 * cryptographic RNG. Framework-free; the single place the code shape lives so registration and
 * resend cannot drift.
 */
public class OtpGenerator {

	private static final int BOUND = 1_000_000;

	private final SecureRandom random;

	public OtpGenerator(SecureRandom random) {
		this.random = random;
	}

	public String generate() {
		return String.format("%06d", random.nextInt(BOUND));
	}
}
