package com.laa66.auth.domain.model;

import java.util.UUID;

/**
 * Result of a successful refresh-token rotation: the freshly minted raw opaque token (for the new
 * httpOnly cookie) and the owning account id, whose value becomes the {@code sub} of the new access
 * token. The store returns this only on a valid rotation; every other outcome is an
 * {@link InvalidRefreshTokenException}.
 */
public record RotatedToken(String rawToken, UUID userId) {
}
