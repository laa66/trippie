package com.laa66.auth.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.Executor;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.ThrottleDecision;
import com.laa66.auth.domain.model.ThrottlePolicy;
import com.laa66.auth.domain.model.UserAccount;
import com.laa66.auth.domain.port.in.RequestPasswordReset;
import com.laa66.auth.domain.port.out.OtpThrottle;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Forgot-password use case (flow NEW), framework-free. Issues a {@link OtpPurpose#RESET} code that
 * shares the store/mailer/throttle machinery with verification but lives in a disjoint Redis
 * namespace ({@code auth:otp:reset:*}).
 *
 * <h2>No enumeration — neither by status nor by timing</h2>
 * <ul>
 * <li><b>Status:</b> the endpoint ALWAYS returns 200. An unknown email issues nothing; a real
 * account past its send-cap is a silent no-op (the limit still protects, it just does not signal).
 * There is no 429 and no 400 on this path, so the response never distinguishes account existence or
 * throttle state.</li>
 * <li><b>Throttle keyed by the email, not the user:</b> {@link #tryAcquire} runs on a SHA-256 hash of
 * the normalized email for BOTH branches, so an existing and a non-existent address consume the same
 * limiter and take the same synchronous Redis work — a probe cannot tell them apart through throttle
 * state either. Only the hash rests in Redis (no PII), under the throttle's own ≤1 h TTLs.</li>
 * <li><b>Timing:</b> the only work that differs between a known and an unknown email — generating,
 * storing, and mailing the code — is handed to an {@link Executor} and so happens off the request
 * thread. The synchronous response time is therefore the same lookup + throttle-check for every
 * input, and stays constant even once a real (blocking) mailer replaces the dev stub in LATER.</li>
 * </ul>
 */
public class ForgotPasswordService implements RequestPasswordReset {

	static final ThrottlePolicy THROTTLE = new ThrottlePolicy(Duration.ofSeconds(60), 5, Duration.ofHours(1));

	private final UserRepository userRepository;
	private final OtpThrottle otpThrottle;
	private final OtpIssuer issuer;
	private final Executor dispatcher;

	public ForgotPasswordService(UserRepository userRepository, OtpThrottle otpThrottle, OtpIssuer issuer,
			Executor dispatcher) {
		this.userRepository = userRepository;
		this.otpThrottle = otpThrottle;
		this.issuer = issuer;
		this.dispatcher = dispatcher;
	}

	@Override
	public void requestReset(String email) {
		// Both branches pay the same synchronous cost: an email-hash throttle write and the account
		// lookup. The throttle runs even for an unknown address so its state cannot be probed.
		ThrottleDecision decision = otpThrottle.tryAcquire(OtpPurpose.RESET, throttleSubject(email), THROTTLE);
		UserAccount account = userRepository.findByEmail(email).orElse(null);

		if (account == null || decision != ThrottleDecision.ALLOWED) {
			return;
		}

		// The only work that would leak timing is pushed off the request thread.
		dispatcher.execute(() -> issuer.issue(OtpPurpose.RESET, account.id(), account.email()));
	}

	private static String throttleSubject(String email) {
		String normalized = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}
