package com.laa66.auth.domain.model;

/**
 * Raised when a request that must carry a bearer access token presents one that is absent, malformed,
 * wrongly signed, wrong-issuer, or already expired. Mapped to a uniform 401 — logout re-verifies the
 * token itself (its {@code jti}/{@code exp} drive the denylist) rather than trusting the gateway's
 * forwarded identity for those claims.
 */
public class InvalidAccessTokenException extends RuntimeException {

	public InvalidAccessTokenException() {
		super("invalid or expired session");
	}
}
