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
 *
 * <p>Both fronts are INDEPENDENTLY optional (M2-19): the boot-time logout replay has no access token at
 * all (it must be able to destroy a session without first creating one), so it revokes the family with a
 * {@code null} {@code jti} and writes nothing to the denylist. Logout therefore only ever de-escalates —
 * every combination of present/absent credential revokes what it can and succeeds.
 */
public interface Logout {

	/**
	 * Revokes the refresh family behind {@code rawRefreshToken} (skipped when absent/blank) and denies
	 * {@code jti} until {@code accessTokenRemainingLife} elapses (skipped when {@code jti} is absent/blank).
	 *
	 * @param rawRefreshToken the raw opaque refresh token from the httpOnly cookie, or {@code null}
	 * @param jti the {@code jti} claim of the verified access token, or {@code null} for a bearer-less
	 * replay — then NOTHING is written to the denylist (there is no token to deny, and inventing one
	 * would mean trusting an unverified identifier)
	 * @param accessTokenRemainingLife exp − now of the presented access token; the denylist TTL, so the
	 * entry self-expires exactly when the token would have anyway (never outlives it, never sooner).
	 * Ignored, and may be {@code null}, when {@code jti} is absent
	 */
	void logout(String rawRefreshToken, String jti, Duration accessTokenRemainingLife);
}
