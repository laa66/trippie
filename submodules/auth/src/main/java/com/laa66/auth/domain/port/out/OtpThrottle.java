package com.laa66.auth.domain.port.out;

import java.util.UUID;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.ThrottleDecision;
import com.laa66.auth.domain.model.ThrottlePolicy;

/**
 * Outbound port guarding OTP send-rate per purpose + <em>subject</em>. The subject is any opaque,
 * stable key the caller chooses: verification/resend key by the known {@code userId}, while
 * forgot-password keys by a hash of the normalized email so the limit applies identically to an
 * existing and a non-existent account (no enumeration through throttle state). {@code tryAcquire}
 * must be atomic: check both limits and, only if allowed, record the send in one indivisible step so
 * concurrent sends cannot both pass. Redis is the reference implementation.
 */
public interface OtpThrottle {

	ThrottleDecision tryAcquire(OtpPurpose purpose, String subject, ThrottlePolicy policy);

	/** Convenience overload for the user-keyed flows (verify/resend), where the account is known. */
	default ThrottleDecision tryAcquire(OtpPurpose purpose, UUID userId, ThrottlePolicy policy) {
		return tryAcquire(purpose, userId.toString(), policy);
	}
}
