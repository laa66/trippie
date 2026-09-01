package com.laa66.auth.domain.port.in;

import com.laa66.auth.domain.model.RefreshResult;

/**
 * Inbound port for the token-refresh use case (flow 04): rotate the presented opaque refresh token
 * and, on success, mint a fresh access token. Cookie transport and the CSRF double-submit check are
 * the web adapter's concern and never reach this port — it sees only the raw token.
 */
public interface RefreshTokens {

	RefreshResult refresh(String rawRefreshToken);
}
