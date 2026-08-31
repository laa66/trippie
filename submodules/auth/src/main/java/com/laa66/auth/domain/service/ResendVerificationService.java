package com.laa66.auth.domain.service;

import java.time.Duration;

import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.OtpThrottledException;
import com.laa66.auth.domain.model.ThrottleDecision;
import com.laa66.auth.domain.model.ThrottlePolicy;
import com.laa66.auth.domain.model.UserAccount;
import com.laa66.auth.domain.port.in.ResendVerification;
import com.laa66.auth.domain.port.out.OtpThrottle;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Resend-OTP use case (flow NEW), framework-free. Only a real, still-unverified account issues a
 * fresh code, and only through the send-throttle (60 s between sends, 5/hour); past the cap it
 * throws {@link OtpThrottledException} (429). An unknown or already-verified email is a silent
 * no-op — no OTP, no throttle spend — so the endpoint does not enumerate accounts. The throttle
 * counts resends only; the sign-up send is not counted, so one resend right after sign-up is
 * allowed before the 60 s / 5-per-hour limits bite.
 */
public class ResendVerificationService implements ResendVerification {

	static final ThrottlePolicy THROTTLE = new ThrottlePolicy(Duration.ofSeconds(60), 5, Duration.ofHours(1));

	private final UserRepository userRepository;
	private final OtpThrottle otpThrottle;
	private final VerificationOtpIssuer issuer;

	public ResendVerificationService(UserRepository userRepository, OtpThrottle otpThrottle,
			VerificationOtpIssuer issuer) {
		this.userRepository = userRepository;
		this.otpThrottle = otpThrottle;
		this.issuer = issuer;
	}

	@Override
	public void resend(String email) {
		UserAccount account = userRepository.findByEmail(email).orElse(null);
		if (account == null || account.emailVerified()) {
			return;
		}

		ThrottleDecision decision = otpThrottle.tryAcquire(OtpPurpose.VERIFY, account.id(), THROTTLE);
		if (decision != ThrottleDecision.ALLOWED) {
			throw new OtpThrottledException();
		}

		issuer.issue(account.id(), account.email());
	}
}
