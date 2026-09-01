package com.laa66.auth.domain.service;

import com.laa66.auth.domain.model.RefreshResult;
import com.laa66.auth.domain.model.RotatedToken;
import com.laa66.auth.domain.port.in.MintAccessToken;
import com.laa66.auth.domain.port.in.RefreshTokens;
import com.laa66.auth.domain.port.out.RefreshTokenStore;

/**
 * Token-refresh use case (flow 04), framework-free. Rotation happens first: only a token that
 * actually rotated yields a new family pointer and an account id, and only then is a fresh access
 * token minted — with {@code sub} taken from the rotation result, never from the request. A reused or
 * expired token throws before any token is minted (surfaced as a uniform 401 by the web layer).
 */
public class RefreshService implements RefreshTokens {

	private final RefreshTokenStore refreshTokenStore;
	private final MintAccessToken mintAccessToken;

	public RefreshService(RefreshTokenStore refreshTokenStore, MintAccessToken mintAccessToken) {
		this.refreshTokenStore = refreshTokenStore;
		this.mintAccessToken = mintAccessToken;
	}

	@Override
	public RefreshResult refresh(String rawRefreshToken) {
		RotatedToken rotated = refreshTokenStore.rotate(rawRefreshToken);
		String accessToken = mintAccessToken.mint(rotated.userId());
		return new RefreshResult(accessToken, rotated.rawToken());
	}
}
