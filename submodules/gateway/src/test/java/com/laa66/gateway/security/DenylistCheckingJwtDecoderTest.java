package com.laa66.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Docker-free unit proof of {@link DenylistCheckingJwtDecoder}: the whole M2-12 security gate was
 * previously exercised only by Testcontainers ITs, so with Docker down nothing validated the
 * denylist decorator (the same gap Light flagged as M2-08 H1, which forced a Docker-free
 * {@code AccessTokenVerifierTest}). Here the delegate {@link ReactiveJwtDecoder} and the
 * {@link ReactiveStringRedisTemplate} are mocked and the reactive outcome is asserted with
 * {@link StepVerifier}.
 *
 * <p>Every case is mutation-pinned: dropping the denylist {@code flatMap} or flipping the
 * fail-closed {@code onErrorMap} to fail-open turns the matching assertion red.
 */
@ExtendWith(MockitoExtension.class)
class DenylistCheckingJwtDecoderTest {

	private static final String TOKEN = "header.payload.signature";
	private static final String JTI = UUID.randomUUID().toString();

	@Mock
	private ReactiveJwtDecoder delegate;

	@Mock
	private ReactiveStringRedisTemplate redis;

	private DenylistCheckingJwtDecoder decoder;

	@BeforeEach
	void setUp() {
		decoder = new DenylistCheckingJwtDecoder(delegate, redis);
	}

	@Test
	void jtiNotOnDenylist_emitsDelegateJwt_andLooksUpTheExactKey() {
		Jwt jwt = validJwt();
		when(delegate.decode(TOKEN)).thenReturn(Mono.just(jwt));
		when(redis.hasKey(anyString())).thenReturn(Mono.just(false));

		StepVerifier.create(decoder.decode(TOKEN))
				.expectNext(jwt)
				.verifyComplete();

		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		verify(redis).hasKey(keyCaptor.capture());
		assertThat(keyCaptor.getValue()).isEqualTo("auth:denylist:" + JTI);
		verify(delegate, times(1)).decode(TOKEN);
	}

	/**
	 * Denylist HIT must surface as {@link BadJwtException} specifically — NOT a bare
	 * {@link JwtException}, which the resource server maps to a 500 instead of a 401. Dropping the
	 * denylist {@code flatMap} would let the delegate's {@code Jwt} leak through as a success, turning
	 * the {@code expectError} red.
	 */
	@Test
	void jtiOnDenylist_errorsWithBadJwtException_notPlainJwtException() {
		when(delegate.decode(TOKEN)).thenReturn(Mono.just(validJwt()));
		when(redis.hasKey(anyString())).thenReturn(Mono.just(true));

		StepVerifier.create(decoder.decode(TOKEN))
				.expectErrorSatisfies(error -> assertThat(error)
						.isInstanceOf(BadJwtException.class))
				.verify();

		verify(delegate, times(1)).decode(TOKEN);
	}

	/**
	 * Redis unavailable fails CLOSED with a {@link BadJwtException} (-> 401), never admitting the
	 * token. Flipping the {@code onErrorMap} to fail-open (e.g. {@code onErrorReturn(false)}) would
	 * emit the {@code Jwt} and turn the {@code expectError} red.
	 */
	@Test
	void redisError_failsClosed_withBadJwtException() {
		when(delegate.decode(TOKEN)).thenReturn(Mono.just(validJwt()));
		when(redis.hasKey(anyString())).thenReturn(Mono.error(new RuntimeException("redis unreachable")));

		StepVerifier.create(decoder.decode(TOKEN))
				.expectError(BadJwtException.class)
				.verify();

		verify(delegate, times(1)).decode(TOKEN);
	}

	private static Jwt validJwt() {
		Instant now = Instant.now();
		return Jwt.withTokenValue(TOKEN)
				.header("alg", "ES256")
				.jti(JTI)
				.subject("user-1")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(900))
				.build();
	}
}
