package com.laa66.auth.domain.port.out;

import java.util.UUID;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.ThrottleDecision;
import com.laa66.auth.domain.model.ThrottlePolicy;

/**
 * Outbound port guarding OTP send-rate per purpose + user. {@link #tryAcquire} must be atomic:
 * check both limits and, only if allowed, record the send in one indivisible step so concurrent
 * resends cannot both pass. Redis is the reference implementation.
 */
public interface OtpThrottle {

	ThrottleDecision tryAcquire(OtpPurpose purpose, UUID userId, ThrottlePolicy policy);
}
