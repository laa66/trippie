package com.laa66.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import com.laa66.gateway.support.GatewayItSupport;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Docker-free proof of the real validator chain wired in {@link SecurityConfig#jwtDecoder} (LOW-1):
 * a cryptographically valid token that carries no {@code jti} is rejected, so it can never reach the
 * denylist as the unrevocable literal key {@code auth:denylist:null}. Uses an in-JVM JWKS stub and a
 * mocked Redis (the jti gate fires before any Redis lookup), so no container is needed.
 *
 * <p>Mutation: remove the {@code JwtClaimNames.JTI} validator from {@code SecurityConfig} and the
 * jti-less token decodes successfully, turning {@link #validlySignedToken_withoutJti_isRejected} red.
 */
@ExtendWith(MockitoExtension.class)
class SecurityConfigJwtDecoderTest {

	private static final String ISSUER = "https://auth.trippie.local";
	private static final String KID = "test-kid";

	private static HttpServer jwksStub;
	private static ECKey signingKey;

	@Mock
	private ReactiveStringRedisTemplate redis;

	private ReactiveJwtDecoder decoder;

	@BeforeAll
	static void startJwks() throws Exception {
		signingKey = new ECKeyGenerator(Curve.P_256).keyID(KID).generate();
		jwksStub = GatewayItSupport.startJwksServer(signingKey);
	}

	@AfterAll
	static void stopJwks() {
		if (jwksStub != null) {
			jwksStub.stop(0);
		}
	}

	@BeforeEach
	void buildDecoder() {
		decoder = new SecurityConfig().jwtDecoder(GatewayItSupport.jwksUri(jwksStub), ISSUER, redis);
	}

	@Test
	void validlySignedToken_withoutJti_isRejected() {
		// Denylist would answer "not revoked" if the jti gate were absent; the lenient stub lets the
		// mutation (validator removed) surface as a successful decode rather than an NPE.
		lenient().when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
		String token = mintWithoutJti();

		StepVerifier.create(decoder.decode(token))
				.expectError(JwtException.class)
				.verify();
	}

	@Test
	void validlySignedToken_withJti_notDenylisted_decodes() throws Exception {
		when(redis.hasKey(anyString())).thenReturn(Mono.just(false));
		String jti = UUID.randomUUID().toString();
		String token = GatewayItSupport.mint(signingKey, UUID.randomUUID().toString(), ISSUER, jti,
				Instant.now(), Instant.now().plus(15, ChronoUnit.MINUTES));

		StepVerifier.create(decoder.decode(token))
				.assertNext(jwt -> assertThat(jwt.getId()).isEqualTo(jti))
				.verifyComplete();
	}

	/** Mirrors {@link GatewayItSupport#mint} but omits the {@code jti} claim entirely. */
	private static String mintWithoutJti() {
		try {
			Instant now = Instant.now();
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.subject(UUID.randomUUID().toString())
					.issuer(ISSUER)
					.issueTime(Date.from(now))
					.expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)))
					.build();
			JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
					.keyID(signingKey.getKeyID())
					.type(JOSEObjectType.JWT)
					.build();
			SignedJWT jwt = new SignedJWT(header, claims);
			jwt.sign(new ECDSASigner(signingKey.toECPrivateKey()));
			return jwt.serialize();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
