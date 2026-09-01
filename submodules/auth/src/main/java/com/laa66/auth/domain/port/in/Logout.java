package com.laa66.auth.domain.port.in;

import java.time.Duration;

/**
 * Logout use case (flow 05), framework-free. Ends a session on both token fronts at once: it revokes
 * the presented refresh-token family (no further silent-refresh is possible) and writes the access
 * token's {@code jti} to a short-lived denylist so the still-unexpired access JWT stops being honoured
 * at the gateway (M2-12) for the remainder of its life.
 *
 * <p>Deliberately idempotent — an already-revoked family and an already-denylisted {@code jti} are both
 * no-ops — so a client that retries a logout always sees the same success.
 */
public interface Logout {

	/**
	 * Revokes the refresh family behind {@code rawRefreshToken} (skipped when absent/blank) and denies
	 * {@code jti} until {@code accessTokenRemainingLife} elapses.
	 *
	 * @param rawRefreshToken the raw opaque refresh token from the httpOnly cookie, or {@code null}
	 * @param jti the {@code jti} claim of the presented access token
	 * @param accessTokenRemainingLife exp − now of the presented access token; the denylist TTL, so the
	 * entry self-expires exactly when the token would have anyway (never outlives it, never sooner)
	 */
	void logout(String rawRefreshToken, String jti, Duration accessTokenRemainingLife);
}
