package com.laa66.auth.domain.port.out;

import java.util.UUID;

import com.laa66.auth.domain.model.InvalidRefreshTokenException;
import com.laa66.auth.domain.model.RotatedToken;

/**
 * Outbound port for the opaque refresh-token store (Redis). Login is the only write path in M2-06;
 * rotation/reuse-detection (M2-07), logout revoke (M2-08), and reset-all (M2-09) build on the same
 * key layout, documented on the Redis adapter.
 */
public interface RefreshTokenStore {

	/**
	 * Opens a NEW refresh-token family for the user: generates a 256-bit CSPRNG opaque token, stores
	 * its SHA-256 hash (never the raw token) under a fresh family id with a 30-day TTL, and returns
	 * the raw token for the caller to place in the httpOnly cookie.
	 */
	String issueNewFamily(UUID userId);

	/**
	 * Atomically rotates the presented raw token within its family (single-use):
	 * <ul>
	 * <li><b>valid current token</b> → mints a new 256-bit CSPRNG token, repoints the family to it,
	 * keeps the old hash so a later replay is still detectable, and returns the new raw token plus the
	 * owning account id;
	 * <li><b>already-rotated (reused) token</b> → revokes the entire family and signals invalid;
	 * <li><b>absent / expired / unknown token</b> → signals invalid.
	 * </ul>
	 * Every non-success outcome throws {@link InvalidRefreshTokenException} — a single, indistinguishable
	 * failure so the wire cannot tell reuse from expiry. The check-and-swap is one atomic step, so two
	 * concurrent rotations of the same token cannot both succeed (the loser is treated as reuse).
	 */
	RotatedToken rotate(String rawRefreshToken);
}
