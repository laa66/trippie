package com.laa66.auth.infrastructure.security;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.port.out.AccessTokenDenylist;

/**
 * Redis-backed access-token denylist, keyed by {@code jti}. Mirrors the {@code auth:*} key convention
 * of the refresh ({@code auth:refresh:*}) and OTP ({@code auth:otp:*}) stores.
 *
 * <p>Key layout: {@code auth:denylist:{jti}} → {@code "1"} (any small non-null marker), TTL = the
 * access token's remaining life. The gateway (M2-12) does a single fail-closed EXISTS on this key, so
 * the value carries no meaning — only its presence does. Entries self-expire when the token would have,
 * so the set stays bounded with no sweep.
 */
@Component
class RedisAccessTokenDenylist implements AccessTokenDenylist {

	private static final String MARKER = "1";

	private final StringRedisTemplate redis;

	RedisAccessTokenDenylist(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void deny(String jti, Duration ttl) {
		redis.opsForValue().set(key(jti), MARKER, ttl);
	}

	static String key(String jti) {
		return "auth:denylist:" + jti;
	}
}
