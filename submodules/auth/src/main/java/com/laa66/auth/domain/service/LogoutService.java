package com.laa66.auth.domain.service;

import java.time.Duration;

import com.laa66.auth.domain.port.in.Logout;
import com.laa66.auth.domain.port.out.AccessTokenDenylist;
import com.laa66.auth.domain.port.out.RefreshTokenStore;

/**
 * Logout orchestration (flow 05), framework-free. Two independent revocations, both idempotent and both
 * individually skippable — each fires only when its credential was actually presented:
 * <ol>
 * <li>revoke the refresh family behind the presented token (only when a refresh token is actually
 * present — a logout without the cookie still denylists the access token);
 * <li>denylist the access {@code jti} until its remaining life PLUS the gateway clock skew elapses
 * (only when a {@code jti} is actually present — the bearer-less boot replay of M2-19 has no access
 * token to deny, and must not write a denylist entry for one it cannot see).
 * </ol>
 * The web adapter has already parsed/verified the access token, so {@code jti} and the remaining life
 * are trusted inputs here; this service owns only the ordering and the idempotency.
 *
 * <p>The denylist entry deliberately outlives the token by {@code denylistSkew}: the gateway honours a
 * token until {@code exp + skew}, so a shorter denylist entry would reopen a window in which a denied
 * token verifies again. Sized here (not in the verifier) because it is a logout policy, not a property
 * of the token's true remaining life.
 */
public class LogoutService implements Logout {

	private final RefreshTokenStore refreshTokenStore;
	private final AccessTokenDenylist denylist;
	private final Duration denylistSkew;

	public LogoutService(RefreshTokenStore refreshTokenStore, AccessTokenDenylist denylist, Duration denylistSkew) {
		this.refreshTokenStore = refreshTokenStore;
		this.denylist = denylist;
		this.denylistSkew = denylistSkew;
	}

	@Override
	public void logout(String rawRefreshToken, String jti, Duration accessTokenRemainingLife) {
		if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
			refreshTokenStore.deleteByToken(rawRefreshToken);
		}
		if (jti != null && !jti.isBlank()) {
			denylist.deny(jti, accessTokenRemainingLife.plus(denylistSkew));
		}
	}
}
