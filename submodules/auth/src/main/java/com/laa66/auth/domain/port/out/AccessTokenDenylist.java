package com.laa66.auth.domain.port.out;

import java.time.Duration;

/**
 * Outbound port for the access-token revocation denylist (Redis). Logout (M2-08) is the only writer;
 * the reactive gateway (M2-12) is the reader — a single fail-closed key-exists on every protected
 * request. An entry lives only for the token's remaining life, so the denylist stays bounded without
 * a sweep: once a denied token would have expired anyway, its entry is already gone.
 */
public interface AccessTokenDenylist {

	/**
	 * Denies {@code jti} for {@code ttl}. Writing the same {@code jti} again simply refreshes the entry
	 * (idempotent), so a retried logout is harmless.
	 */
	void deny(String jti, Duration ttl);
}
