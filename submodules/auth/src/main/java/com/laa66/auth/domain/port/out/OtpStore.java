package com.laa66.auth.domain.port.out;

import java.time.Duration;
import java.util.UUID;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.OtpVerificationResult;

/**
 * Outbound port for the one-time-code store. Implementations persist the code in a non-reversible
 * form under a TTL, keyed by purpose + user; the raw code never rests at the store.
 */
public interface OtpStore {

	/**
	 * Issues a fresh code, overwriting any prior one and resetting its attempt counter so the user
	 * gets a full attempt budget again.
	 */
	void store(OtpPurpose purpose, UUID userId, String code, Duration ttl);

	/**
	 * Atomically checks a submitted code against the stored one and enforces the attempt cap in a
	 * single indivisible step (no TOCTOU between concurrent verifies): increments the attempt
	 * counter; the {@code maxAttempts + 1}-th attempt fails and invalidates the code; a match
	 * consumes the code (OTP + counter deleted). See {@link OtpVerificationResult}.
	 */
	OtpVerificationResult verify(OtpPurpose purpose, UUID userId, String code, int maxAttempts);
}
