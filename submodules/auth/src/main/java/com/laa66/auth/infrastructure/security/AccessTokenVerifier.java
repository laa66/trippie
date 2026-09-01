package com.laa66.auth.infrastructure.security;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.laa66.auth.domain.model.InvalidAccessTokenException;

/**
 * Verifies an incoming ES256 access token and extracts the two claims logout needs — {@code jti} and
 * the remaining life (exp − now). It re-checks the signature with the same key the minter signs with
 * (public half of the {@code signingKey} bean), so a tampered or foreign token is rejected here even
 * though the gateway already gated the route: logout must not denylist a {@code jti} it cannot trust.
 *
 * <p>Every failure — malformed, bad signature, wrong issuer, missing {@code jti}/{@code exp}, or
 * already expired — collapses to {@link InvalidAccessTokenException} (uniform 401). No starter-security:
 * this reuses only the Nimbus JOSE machinery from M2-05.
 */
public class AccessTokenVerifier {

	private final ECDSAVerifier verifier;
	private final String issuer;
	private final Clock clock;

	public AccessTokenVerifier(ECKey signingKey, String issuer, Clock clock) {
		try {
			this.verifier = new ECDSAVerifier(signingKey.toECPublicKey());
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("signing key has no usable EC public part", ex);
		}
		this.issuer = issuer;
		this.clock = clock;
	}

	/** Verifies {@code compactJwt} and returns its {@code jti} plus positive remaining life, or throws. */
	public VerifiedAccessToken verify(String compactJwt) {
		if (compactJwt == null || compactJwt.isBlank()) {
			throw new InvalidAccessTokenException();
		}
		try {
			SignedJWT jwt = SignedJWT.parse(compactJwt);
			if (!jwt.verify(verifier)) {
				throw new InvalidAccessTokenException();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Date exp = claims.getExpirationTime();
			String jti = claims.getJWTID();
			if (jti == null || jti.isBlank() || exp == null || !issuer.equals(claims.getIssuer())) {
				throw new InvalidAccessTokenException();
			}
			Instant now = clock.instant();
			Instant expiry = exp.toInstant();
			if (!expiry.isAfter(now)) {
				throw new InvalidAccessTokenException();
			}
			return new VerifiedAccessToken(jti, Duration.between(now, expiry));
		}
		catch (ParseException | JOSEException ex) {
			throw new InvalidAccessTokenException();
		}
	}

	/** The trusted claims logout pulls from the bearer token: its {@code jti} and remaining life. */
	public record VerifiedAccessToken(String jti, Duration remainingLife) {
	}
}
