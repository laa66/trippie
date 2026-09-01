package com.laa66.auth.domain.model;

/**
 * Outcome of a successful login: the short-lived access JWT (returned in the response body) and the
 * raw opaque refresh token (placed in an httpOnly cookie by the web layer, never in the body).
 */
public record LoginResult(String accessToken, String refreshToken) {
}
