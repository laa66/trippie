package com.laa66.auth.infrastructure.otp;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.ThrottleDecision;
import com.laa66.auth.domain.model.ThrottlePolicy;
import com.laa66.auth.domain.port.out.OtpThrottle;

/**
 * Redis send-throttle for OTP issuance. Two keys per purpose+user: a {@code …:cooldown} marker
 * (existence = within the minimum inter-send gap) and a {@code …:sends} counter over a fixed
 * window. Both are checked-and-set in one Lua script so concurrent resends cannot both slip through;
 * both carry native TTLs so nothing lingers past its window. A cooldown-blocked attempt does not
 * spend window budget, and the window counter's TTL is anchored at the first send (a fixed window,
 * not a sliding one that a spammer could keep alive).
 */
@Component
class RedisOtpThrottle implements OtpThrottle {

	// KEYS[1]=cooldown, KEYS[2]=sends; ARGV[1]=cooldownSec, ARGV[2]=maxSends, ARGV[3]=windowSec.
	// Returns OK | COOLDOWN | HOURLY.
	private static final RedisScript<String> ACQUIRE = RedisScript.of("""
			if redis.call('EXISTS', KEYS[1]) == 1 then
			  return 'COOLDOWN'
			end
			local count = redis.call('INCR', KEYS[2])
			if count == 1 then
			  redis.call('EXPIRE', KEYS[2], ARGV[3])
			end
			if count > tonumber(ARGV[2]) then
			  return 'HOURLY'
			end
			redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
			return 'OK'
			""", String.class);

	private final StringRedisTemplate redis;

	RedisOtpThrottle(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public ThrottleDecision tryAcquire(OtpPurpose purpose, String subject, ThrottlePolicy policy) {
		String result = redis.execute(ACQUIRE,
				List.of(cooldownKey(purpose, subject), sendsKey(purpose, subject)),
				Long.toString(policy.cooldown().toSeconds()),
				Integer.toString(policy.maxSends()),
				Long.toString(policy.window().toSeconds()));
		return switch (result) {
			case "OK" -> ThrottleDecision.ALLOWED;
			case "COOLDOWN" -> ThrottleDecision.COOLDOWN;
			case "HOURLY" -> ThrottleDecision.HOURLY_CAP;
			default -> throw new IllegalStateException("unexpected throttle result: " + result);
		};
	}

	// Same string shape as before for the userId subject (auth:otp:{slug}:{userId}:cooldown), so the
	// verify/resend keys are unchanged; forgot passes an email-hash subject into the same layout.
	static String cooldownKey(OtpPurpose purpose, String subject) {
		return "auth:otp:" + purpose.slug() + ":" + subject + ":cooldown";
	}

	static String sendsKey(OtpPurpose purpose, String subject) {
		return "auth:otp:" + purpose.slug() + ":" + subject + ":sends";
	}
}
