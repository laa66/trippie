package com.laa66.auth.domain.service;

import com.laa66.auth.domain.model.InvalidOtpException;
import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.OtpVerificationResult;
import com.laa66.auth.domain.model.UserAccount;
import com.laa66.auth.domain.port.in.VerifyEmail;
import com.laa66.auth.domain.port.out.OtpStore;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Email-verification use case (flow NEW), framework-free. Resolves the account by email, delegates
 * the atomic check+attempt-cap to the store, and flips {@code email_verified} on success. Every
 * failure — unknown email, wrong/expired/absent code, attempt cap — throws the same
 * {@link InvalidOtpException} (one generic 400, no oracle); an already-verified account is an
 * idempotent no-op (the boundary returns 200) and never touches the OTP store.
 */
public class VerifyEmailService implements VerifyEmail {

	static final int MAX_ATTEMPTS = 5;

	private final UserRepository userRepository;
	private final OtpStore otpStore;

	public VerifyEmailService(UserRepository userRepository, OtpStore otpStore) {
		this.userRepository = userRepository;
		this.otpStore = otpStore;
	}

	@Override
	public void verify(String email, String code) {
		UserAccount account = userRepository.findByEmail(email).orElseThrow(InvalidOtpException::new);
		if (account.emailVerified()) {
			return;
		}

		OtpVerificationResult result = otpStore.verify(OtpPurpose.VERIFY, account.id(), code, MAX_ATTEMPTS);
		if (result != OtpVerificationResult.SUCCESS) {
			throw new InvalidOtpException();
		}

		userRepository.markVerified(account.id());
	}
}
