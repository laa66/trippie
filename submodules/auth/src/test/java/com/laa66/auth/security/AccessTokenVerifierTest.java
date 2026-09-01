package com.laa66.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.laa66.auth.domain.model.InvalidAccessTokenException;
import com.laa66.auth.infrastructure.security.AccessTokenVerifier;
import com.laa66.auth.infrastructure.security.AccessTokenVerifier.VerifiedAccessToken;

/**
 * Docker-free unit proof of {@link AccessTokenVerifier}: it accepts only a token that is genuinely
 * signed by the configured key, from the configured issuer, unexpired, and carrying a {@code jti} —
 * and it reports the token's TRUE remaining life (exp − now), not a constant. Every check is pinned by
 * a test that turns red when that check is removed from the verifier (mutations noted in the report).
 *
 * <p>Tokens are minted locally with the same EC machinery M2-05 uses, against a fixed {@link Clock} so
 * the remaining-life arithmetic is deterministic.
 */
class AccessTokenVerifierTest {

	private static final String ISSUER = "https://auth.trippie.local";
	private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final ECKey signingKey = ecKey();
	private final AccessTokenVerifier verifier = new AccessTokenVerifier(signingKey, ISSUER, CLOCK);

	@Test
	void validToken_returnsJti_andTrueRemainingLife() {
		String jti = UUID.randomUUID().toString();
		String token = mint(signingKey, ISSUER, jti, NOW.plus(Duration.ofMinutes(15)));

		VerifiedAccessToken verified = verifier.verify(token);

		assertThat(verified.jti()).isEqualTo(jti);
		assertThat(verified.remainingLife()).isEqualTo(Duration.ofMinutes(15));
	}

	/**
	 * M1: the remaining life FOLLOWS the token's exp — a 30 s token yields ~30 s, not the 15 min access
	 * TTL. Turns red if {@code Duration.between(now, expiry)} is ever replaced by a constant.
	 */
	@Test
	void shortLivedToken_remainingLifeFollowsExp_notAConstant() {
		String token = mint(signingKey, ISSUER, UUID.randomUUID().toString(), NOW.plusSeconds(30));

		VerifiedAccessToken verified = verifier.verify(token);

		// Fixed clock → exact arithmetic; the point is it tracks exp (30 s), never the 15 min constant.
		assertThat(verified.remainingLife()).isEqualTo(Duration.ofSeconds(30));
		assertThat(verified.remainingLife()).isLessThan(Duration.ofMinutes(1));
	}

	/** Mutation: drop {@code jwt.verify(...)} → this stays green with a foreign-key signature. */
	@Test
	void foreignKeySignature_isRejected() {
		String token = mint(ecKey(), ISSUER, UUID.randomUUID().toString(), NOW.plus(Duration.ofMinutes(15)));

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void tamperedPayload_breaksSignature_isRejected() {
		String token = mint(signingKey, ISSUER, UUID.randomUUID().toString(), NOW.plus(Duration.ofMinutes(15)));
		String[] parts = token.split("\\.");
		// Re-sign nothing: swap the payload segment for a different one, keeping the original signature.
		String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
				("{\"sub\":\"attacker\",\"iss\":\"" + ISSUER + "\",\"jti\":\"x\",\"exp\":"
						+ (NOW.getEpochSecond() + 900) + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

		assertThatThrownBy(() -> verifier.verify(tampered)).isInstanceOf(InvalidAccessTokenException.class);
	}

	/** Mutation: drop the issuer check → a valid signature from a foreign issuer would slip through. */
	@Test
	void foreignIssuer_isRejected() {
		String token = mint(signingKey, "https://evil.example", UUID.randomUUID().toString(),
				NOW.plus(Duration.ofMinutes(15)));

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
	}

	/** Mutation: drop the {@code !expiry.isAfter(now)} check → an expired token would be accepted. */
	@Test
	void expiredToken_isRejected() {
		String token = mint(signingKey, ISSUER, UUID.randomUUID().toString(), NOW.minusSeconds(1));

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
	}

	/** Mutation: drop the {@code jti == null} check → a jti-less token would denylist a null key. */
	@Test
	void missingJti_isRejected() {
		String token = mint(signingKey, ISSUER, null, NOW.plus(Duration.ofMinutes(15)));

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void missingExp_isRejected() {
		String token = mint(signingKey, ISSUER, UUID.randomUUID().toString(), null);

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void wrongAlgorithm_isRejected() throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(UUID.randomUUID().toString())
				.issuer(ISSUER)
				.jwtID(UUID.randomUUID().toString())
				.expirationTime(Date.from(NOW.plus(Duration.ofMinutes(15))))
				.build();
		SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
		hs.sign(new MACSigner("0123456789012345678901234567890123456789")); // 320-bit HMAC key
		String token = hs.serialize();

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void malformedCompact_isRejected() {
		assertThatThrownBy(() -> verifier.verify("not-a-jwt")).isInstanceOf(InvalidAccessTokenException.class);
	}

	@Test
	void nullOrBlankCompact_isRejected() {
		assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(InvalidAccessTokenException.class);
		assertThatThrownBy(() -> verifier.verify("")).isInstanceOf(InvalidAccessTokenException.class);
		assertThatThrownBy(() -> verifier.verify("   ")).isInstanceOf(InvalidAccessTokenException.class);
	}

	private static ECKey ecKey() {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID("test-" + UUID.randomUUID()).generate();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String mint(ECKey key, String issuer, String jti, Instant exp) {
		try {
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
					.subject(UUID.randomUUID().toString())
					.issuer(issuer)
					.issueTime(Date.from(NOW));
			if (jti != null) {
				claims.jwtID(jti);
			}
			if (exp != null) {
				claims.expirationTime(Date.from(exp));
			}
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims.build());
			jwt.sign(new ECDSASigner(key.toECPrivateKey()));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
