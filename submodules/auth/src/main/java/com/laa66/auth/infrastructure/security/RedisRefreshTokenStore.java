package com.laa66.auth.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.model.InvalidRefreshTokenException;
import com.laa66.auth.domain.model.RotatedToken;
import com.laa66.auth.domain.port.out.RefreshTokenStore;

/**
 * Redis-backed opaque refresh-token store. Only the SHA-256 hash of a token is ever persisted.
 *
 * <h2>Key layout (shared by M2-06 login and, later, M2-07 refresh / M2-08 logout / M2-09 reset)</h2>
 * <ul>
 * <li>{@code auth:refresh:tok:{tokenHash}} → {@code {userId}:{familyId}} (TTL 30d) — reverse index
 * from a presented token to its owning family. O(1) lookup on refresh/logout.</li>
 * <li>{@code auth:refresh:fam:{userId}:{familyId}} → {@code {currentTokenHash}} (TTL 30d) — the
 * family's currently valid token. Its existence == the family is alive; deleting it revokes the
 * whole family.</li>
 * <li>{@code auth:refresh:user:{userId}} → SET of {@code familyId} (TTL 30d) — every family a user
 * owns, so a password reset can revoke them all.</li>
 * </ul>
 *
 * <p><b>How later tasks use it.</b> Refresh (M2-07): hash the presented token, {@code GET tok:*};
 * absent → 401; else read {@code fam:*} — if it equals the presented hash, rotate (write a new
 * token + repoint {@code fam:*}, KEEP the old {@code tok:*} entry so a replay is still detectable);
 * if it differs, the token was already rotated → reuse → {@code DEL fam:*} revokes the family (401).
 * Logout (M2-08): {@code DEL fam:*} for the presented token's family. Reset (M2-09):
 * {@code SMEMBERS user:*} then {@code DEL fam:*} for each. Stale {@code tok:*} entries simply expire.
 */
@Component
class RedisRefreshTokenStore implements RefreshTokenStore {

	/** Frozen decision: refresh tokens live 30 days. */
	static final Duration TTL = Duration.ofDays(30);

	private static final int TOKEN_BYTES = 32; // 256-bit opaque token

	// KEYS[1]=tok:{hash}, KEYS[2]=fam:{userId}:{familyId}, KEYS[3]=user:{userId}
	// ARGV[1]=tok value (userId:familyId), ARGV[2]=tokenHash, ARGV[3]=familyId, ARGV[4]=ttlSeconds
	private static final RedisScript<String> STORE_FAMILY = RedisScript.of("""
			redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[4])
			redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[4])
			redis.call('SADD', KEYS[3], ARGV[3])
			redis.call('EXPIRE', KEYS[3], ARGV[4])
			return 'OK'
			""", String.class);

	// Atomic check-and-swap rotation. KEYS[1]=tok:{presentedHash}; the family and new-token keys are
	// derived inside the script from the stored {userId}:{familyId} value (single Redis node — the
	// dynamic key names are why this is not cluster-safe, which the deployment is not).
	// ARGV[1]=presentedHash, ARGV[2]=newHash, ARGV[3]=ttlSeconds.
	// Returns 'OK:{userId}' on rotation, 'REUSE' when the token was already rotated (family revoked),
	// 'FAIL' when absent/expired/unknown. REUSE and FAIL are the same 401 to the caller.
	private static final RedisScript<String> ROTATE = RedisScript.of("""
			local tokVal = redis.call('GET', KEYS[1])
			if not tokVal then
				return 'FAIL'
			end
			local sep = string.find(tokVal, ':', 1, true)
			local userId = string.sub(tokVal, 1, sep - 1)
			local familyId = string.sub(tokVal, sep + 1)
			local famKey = 'auth:refresh:fam:' .. userId .. ':' .. familyId
			local famHash = redis.call('GET', famKey)
			if not famHash then
				return 'FAIL'
			end
			if famHash ~= ARGV[1] then
				redis.call('DEL', famKey)
				return 'REUSE'
			end
			redis.call('SET', 'auth:refresh:tok:' .. ARGV[2], tokVal, 'EX', ARGV[3])
			redis.call('SET', famKey, ARGV[2], 'EX', ARGV[3])
			redis.call('SADD', 'auth:refresh:user:' .. userId, familyId)
			redis.call('EXPIRE', 'auth:refresh:user:' .. userId, ARGV[3])
			return 'OK:' .. userId
			""", String.class);

	private final StringRedisTemplate redis;
	private final SecureRandom random;

	RedisRefreshTokenStore(StringRedisTemplate redis, SecureRandom random) {
		this.redis = redis;
		this.random = random;
	}

	@Override
	public String issueNewFamily(UUID userId) {
		byte[] raw = new byte[TOKEN_BYTES];
		random.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		String tokenHash = sha256Hex(token);
		UUID familyId = UUID.randomUUID();

		redis.execute(STORE_FAMILY,
				List.of(tokKey(tokenHash), famKey(userId, familyId), userKey(userId)),
				userId + ":" + familyId, tokenHash, familyId.toString(), Long.toString(TTL.toSeconds()));

		return token;
	}

	@Override
	public RotatedToken rotate(String rawRefreshToken) {
		String presentedHash = sha256Hex(rawRefreshToken);

		// The candidate token is generated here (crypto-secure RNG stays out of Lua); its hash is
		// written by the script only if the rotation actually happens, otherwise it is simply unused.
		byte[] raw = new byte[TOKEN_BYTES];
		random.nextBytes(raw);
		String newToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		String newHash = sha256Hex(newToken);

		String result = redis.execute(ROTATE, List.of(tokKey(presentedHash)),
				presentedHash, newHash, Long.toString(TTL.toSeconds()));

		if (result == null || !result.startsWith("OK:")) {
			throw new InvalidRefreshTokenException();
		}
		UUID userId = UUID.fromString(result.substring("OK:".length()));
		return new RotatedToken(newToken, userId);
	}

	static String tokKey(String tokenHash) {
		return "auth:refresh:tok:" + tokenHash;
	}

	static String famKey(UUID userId, UUID familyId) {
		return "auth:refresh:fam:" + userId + ":" + familyId;
	}

	static String userKey(UUID userId) {
		return "auth:refresh:user:" + userId;
	}

	static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
