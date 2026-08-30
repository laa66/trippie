package com.laa66.auth.infrastructure.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.port.out.OtpStore;

/**
 * Stores the SHA-256 hash of the code (never the raw code) under {@code auth:otp:{slug}:{userId}}
 * with a native Redis TTL, so an expired code disappears on its own. M2-04 hashes the submitted
 * code the same way to verify.
 */
@Component
class RedisOtpStore implements OtpStore {

	private final StringRedisTemplate redis;

	RedisOtpStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void store(OtpPurpose purpose, UUID userId, String code, Duration ttl) {
		redis.opsForValue().set(key(purpose, userId), sha256Hex(code), ttl);
	}

	static String key(OtpPurpose purpose, UUID userId) {
		return "auth:otp:" + purpose.slug() + ":" + userId;
	}

	static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
