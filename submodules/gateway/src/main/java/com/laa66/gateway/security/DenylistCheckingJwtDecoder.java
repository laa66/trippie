package com.laa66.gateway.security;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import reactor.core.publisher.Mono;

/**
 * Wraps a {@link ReactiveJwtDecoder} with auth's revocation denylist (flow 03 / flow 05). After the
 * delegate has cryptographically validated the token (signature + exp + iss + sub), this runs a
 * single non-blocking {@code EXISTS auth:denylist:{jti}} against Redis — matching the key logout
 * writes (M2-08) — and rejects a denied token with a 401.
 *
 * <p><b>Fail-closed.</b> A Redis error (down / timeout) is mapped to a {@link JwtException}, so a
 * protected request errors with 401 rather than being admitted: this is a security gate, deliberately
 * unlike the spatial cache's fail-open policy. The whole path stays on the reactive chain — no
 * {@code block()} touches the Netty event loop.
 */
class DenylistCheckingJwtDecoder implements ReactiveJwtDecoder {

	private static final String KEY_PREFIX = "auth:denylist:";

	private final ReactiveJwtDecoder delegate;
	private final ReactiveStringRedisTemplate redis;

	DenylistCheckingJwtDecoder(ReactiveJwtDecoder delegate, ReactiveStringRedisTemplate redis) {
		this.delegate = delegate;
		this.redis = redis;
	}

	@Override
	public Mono<Jwt> decode(String token) throws JwtException {
		return delegate.decode(token).flatMap(this::rejectIfDenylisted);
	}

	private Mono<Jwt> rejectIfDenylisted(Jwt jwt) {
		return redis.hasKey(KEY_PREFIX + jwt.getId())
				// onErrorMap applies ONLY to the Redis lookup — a lookup failure fails closed, never
				// swallowed into a success. BadJwtException (not a plain JwtException, which the
				// resource server treats as a 500 AuthenticationServiceException) makes both the
				// lookup-failure and the denylist-hit below surface as a 401 invalid-token.
				.onErrorMap(error -> new BadJwtException("denylist check unavailable; failing closed", error))
				.flatMap(denied -> Boolean.TRUE.equals(denied)
						? Mono.error(new BadJwtException("access token has been revoked"))
						: Mono.just(jwt));
	}
}
