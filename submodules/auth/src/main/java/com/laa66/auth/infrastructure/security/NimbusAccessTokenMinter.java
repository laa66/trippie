package com.laa66.auth.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.laa66.auth.domain.port.in.MintAccessToken;

/**
 * Hand-rolled ES256 access-token minter. Each token carries {@code sub} (user UUID), {@code iss},
 * {@code iat}, {@code exp} (iat + TTL), and a random {@code jti}; the header pins {@code alg=ES256}
 * and the active {@code kid}. The private key never leaves this adapter.
 */
public class NimbusAccessTokenMinter implements MintAccessToken {

	private final String kid;
	private final ECDSASigner signer;
	private final String issuer;
	private final Duration ttl;
	private final Clock clock;

	public NimbusAccessTokenMinter(ECKey signingKey, String issuer, Duration ttl, Clock clock) {
		try {
			this.signer = new ECDSASigner(signingKey.toECPrivateKey());
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("signing key is not a usable EC private key", ex);
		}
		this.kid = signingKey.getKeyID();
		this.issuer = issuer;
		this.ttl = ttl;
		this.clock = clock;
	}

	@Override
	public String mint(UUID userId) {
		Instant now = clock.instant();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.issuer(issuer)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(ttl)))
				.jwtID(UUID.randomUUID().toString())
				.build();
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
				.keyID(kid)
				.type(JOSEObjectType.JWT)
				.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		try {
			jwt.sign(signer);
		}
		catch (JOSEException ex) {
			throw new IllegalStateException("failed to sign access token", ex);
		}
		return jwt.serialize();
	}
}
