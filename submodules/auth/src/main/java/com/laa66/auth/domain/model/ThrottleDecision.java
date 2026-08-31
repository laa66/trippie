package com.laa66.auth.domain.model;

/**
 * Outcome of a send-throttle acquisition. Only {@link #ALLOWED} permits issuing a new OTP; the two
 * denial reasons are kept distinct for logging but the service maps both to a single 429.
 */
public enum ThrottleDecision {
	/** Under both limits; the send was recorded (cooldown armed, window counter incremented). */
	ALLOWED,
	/** A send happened within the minimum inter-send gap (60 s). */
	COOLDOWN,
	/** The per-window send cap (5/hour) is already reached. */
	HOURLY_CAP
}
