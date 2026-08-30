package com.laa66.auth.domain.port.out;

import java.time.Duration;
import java.util.UUID;

import com.laa66.auth.domain.model.OtpPurpose;

/**
 * Outbound port for the one-time-code store. Implementations persist the code in a non-reversible
 * form under a TTL, keyed by purpose + user; the raw code never rests at the store.
 */
public interface OtpStore {

	void store(OtpPurpose purpose, UUID userId, String code, Duration ttl);
}
