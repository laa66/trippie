package com.laa66.auth.domain.model;

/**
 * Outcome of checking a submitted OTP against the store. Only {@link #SUCCESS} lets the caller
 * proceed; the service collapses every failure into one generic 400 so {@code /verify} never
 * distinguishes a wrong code from an expired, absent, or attempt-exhausted one (no oracle).
 */
public enum OtpVerificationResult {
	/** Code matched within TTL and attempt budget; the store consumed it (OTP + counter deleted). */
	SUCCESS,
	/** Code did not match but the OTP is still alive; the attempt counter was incremented. */
	INVALID,
	/** The attempt cap was exceeded (the 6th attempt); the store invalidated the code. */
	TOO_MANY_ATTEMPTS,
	/** No live OTP for this user (expired via TTL, never issued, or already consumed). */
	ABSENT
}
