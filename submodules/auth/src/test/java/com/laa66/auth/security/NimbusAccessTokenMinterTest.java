package com.laa66.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.laa66.auth.infrastructure.security.NimbusAccessTokenMinter;

/**
 * Unit proof of the ES256 minter: every claim and header field is present and correct, the
 * signature verifies with the public key, and a body-tampered token is rejected. An ephemeral P-256
 * key is generated in-test (a fixture, not a per-restart production key).
 */
class NimbusAccessTokenMinterTest {

	private static final String ISSUER = "https://auth.trippie.test";
	private static final Duration TTL = Duration.ofMinutes(15);
	private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

	private ECKey signingKey;
	private NimbusAccessTokenMinter minter;

	@BeforeEach
	void setUp() throws Exception {
		signingKey = new ECKeyGenerator(Curve.P_256).keyID("kid-unit-1").generate();
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		minter = new NimbusAccessTokenMinter(signingKey, ISSUER, TTL, clock);
	}

	@Test
	void mint_setsHeaderAlgAndKid() throws Exception {
		SignedJWT jwt = SignedJWT.parse(minter.mint(UUID.randomUUID()));

		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
		assertThat(jwt.getHeader().getKeyID()).isEqualTo("kid-unit-1");
		assertThat(jwt.getHeader().getType().getType()).isEqualTo("JWT");
	}

	@Test
	void mint_setsAllRequiredClaims() throws Exception {
		UUID userId = UUID.randomUUID();

		JWTClaimsSet claims = SignedJWT.parse(minter.mint(userId)).getJWTClaimsSet();

		assertThat(claims.getSubject()).isEqualTo(userId.toString());
		assertThat(claims.getIssuer()).isEqualTo(ISSUER);
		assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
		assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(TTL));
		assertThat(claims.getJWTID()).isNotBlank();
		// jti must parse as a UUID (random, high-entropy identifier).
		assertThat(UUID.fromString(claims.getJWTID())).isNotNull();
	}

	@Test
	void mint_expiresExactly15MinutesAfterIat() throws Exception {
		JWTClaimsSet claims = SignedJWT.parse(minter.mint(UUID.randomUUID())).getJWTClaimsSet();

		long lifetimeSeconds = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
		assertThat(lifetimeSeconds).isEqualTo(900);
	}

	@Test
	void mint_producesUniqueJtiPerToken() throws Exception {
		String jti1 = SignedJWT.parse(minter.mint(UUID.randomUUID())).getJWTClaimsSet().getJWTID();
		String jti2 = SignedJWT.parse(minter.mint(UUID.randomUUID())).getJWTClaimsSet().getJWTID();

		assertThat(jti1).isNotEqualTo(jti2);
	}

	@Test
	void mint_signatureVerifiesWithPublicKey() throws Exception {
		SignedJWT jwt = SignedJWT.parse(minter.mint(UUID.randomUUID()));

		assertThat(jwt.verify(new ECDSAVerifier(signingKey.toPublicJWK()))).isTrue();
	}

	@Test
	void mint_bodyTamperedTokenFailsVerification() throws Exception {
		String token = minter.mint(UUID.randomUUID());
		String[] parts = token.split("\\.");

		// Swap the sub claim but keep the original signature: verification must fail.
		JWTClaimsSet tampered = new JWTClaimsSet.Builder(
				SignedJWT.parse(token).getJWTClaimsSet())
				.subject(UUID.randomUUID().toString())
				.build();
		String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(tampered.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		SignedJWT forged = SignedJWT.parse(parts[0] + "." + tamperedPayload + "." + parts[2]);

		assertThat(forged.verify(new ECDSAVerifier(signingKey.toPublicJWK()))).isFalse();
	}
}
