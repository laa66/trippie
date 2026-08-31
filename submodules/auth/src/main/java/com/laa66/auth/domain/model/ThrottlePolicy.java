package com.laa66.auth.domain.model;

import java.time.Duration;

/**
 * Send-rate policy for OTP issuance: at most one send per {@code cooldown}, and at most
 * {@code maxSends} within a rolling {@code window}. The domain owns these numbers (frozen decision:
 * 60 s between sends, 5/hour); the Redis adapter only enforces them.
 */
public record ThrottlePolicy(Duration cooldown, int maxSends, Duration window) {
}
