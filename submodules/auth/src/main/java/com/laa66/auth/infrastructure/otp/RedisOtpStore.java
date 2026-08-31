package com.laa66.auth.infrastructure.otp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.OtpVerificationResult;
import com.laa66.auth.domain.port.out.OtpStore;

/**
 * Stores the SHA-256 hash of the code (never the raw code) under {@code auth:otp:{slug}:{userId}}
 * with a native Redis TTL, so an expired code disappears on its own. The attempt counter lives at
 * {@code …:attempts} and inherits the OTP's remaining TTL, so the two expire together and a stale
 * counter can never wedge an account.
 *
 * <p>Verification runs as a single Lua script so the GET / INCR / DEL sequence is atomic on the
 * Redis event loop: two concurrent verifies can never both consume the code nor lose an attempt
 * increment (no TOCTOU).
 */
@Component
class RedisOtpStore implements OtpStore {

	// KEYS[1]=otp, KEYS[2]=attempts; ARGV[1]=submitted hash, ARGV[2]=maxAttempts.
	// Returns OK | INVALID | TOO_MANY | ABSENT. The (maxAttempts+1)-th attempt kills the code
	// before any comparison, so even a correct code on that attempt fails.
	private static final RedisScript<String> VERIFY = RedisScript.of("""
			local stored = redis.call('GET', KEYS[1])
			if not stored then
			  redis.call('DEL', KEYS[2])
			  return 'ABSENT'
			end
			local attempts = redis.call('INCR', KEYS[2])
			if attempts == 1 then
			  local ttl = redis.call('TTL', KEYS[1])
			  if ttl and ttl > 0 then
			    redis.call('EXPIRE', KEYS[2], ttl)
			  end
			end
			if attempts > tonumber(ARGV[2]) then
			  redis.call('DEL', KEYS[1])
			  redis.call('DEL', KEYS[2])
			  return 'TOO_MANY'
			end
			if stored == ARGV[1] then
			  redis.call('DEL', KEYS[1])
			  redis.call('DEL', KEYS[2])
			  return 'OK'
			end
			return 'INVALID'
			""", String.class);

	private final StringRedisTemplate redis;

	RedisOtpStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void store(OtpPurpose purpose, UUID userId, String code, Duration ttl) {
		redis.opsForValue().set(key(purpose, userId), sha256Hex(code), ttl);
		// A fresh code resets the attempt budget (no-op on first issuance).
		redis.delete(attemptsKey(purpose, userId));
	}

	@Override
	public OtpVerificationResult verify(OtpPurpose purpose, UUID userId, String code, int maxAttempts) {
		String result = redis.execute(VERIFY,
				List.of(key(purpose, userId), attemptsKey(purpose, userId)),
				sha256Hex(code), Integer.toString(maxAttempts));
		return switch (result) {
			case "OK" -> OtpVerificationResult.SUCCESS;
			case "INVALID" -> OtpVerificationResult.INVALID;
			case "TOO_MANY" -> OtpVerificationResult.TOO_MANY_ATTEMPTS;
			case "ABSENT" -> OtpVerificationResult.ABSENT;
			default -> throw new IllegalStateException("unexpected OTP verify result: " + result);
		};
	}

	static String key(OtpPurpose purpose, UUID userId) {
		return "auth:otp:" + purpose.slug() + ":" + userId;
	}

	static String attemptsKey(OtpPurpose purpose, UUID userId) {
		return key(purpose, userId) + ":attempts";
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
