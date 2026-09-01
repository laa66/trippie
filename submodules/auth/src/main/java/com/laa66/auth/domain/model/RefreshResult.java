package com.laa66.auth.domain.model;

/**
 * Outcome of a successful refresh (flow 04): a new short-lived access JWT (returned in the response
 * body) and the rotated raw refresh token (placed in the httpOnly cookie by the web layer, never in
 * the body). Mirrors {@link LoginResult}.
 */
public record RefreshResult(String accessToken, String refreshToken) {
}
