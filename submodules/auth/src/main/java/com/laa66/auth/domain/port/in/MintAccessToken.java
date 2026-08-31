package com.laa66.auth.domain.port.in;

import java.util.UUID;

/**
 * Inbound port for minting a short-lived access token (flow 02/03). Returns the signed compact JWT
 * carrying {@code sub}/{@code iss}/{@code iat}/{@code exp}/{@code jti}; the signing algorithm, key,
 * and TTL are infrastructure concerns behind the adapter.
 */
public interface MintAccessToken {

	String mint(UUID userId);
}
