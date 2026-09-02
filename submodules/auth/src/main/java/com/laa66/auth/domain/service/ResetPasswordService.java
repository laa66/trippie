package com.laa66.auth.domain.service;

import com.laa66.auth.domain.model.InvalidOtpException;
import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.OtpVerificationResult;
import com.laa66.auth.domain.model.UserAccount;
import com.laa66.auth.domain.port.in.ResetPassword;
import com.laa66.auth.domain.port.out.OtpStore;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.RefreshTokenStore;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Reset-password use case (flow NEW), framework-free. Resolves the account by email, delegates the
 * atomic check+attempt-cap to the store under the {@link OtpPurpose#RESET} namespace (same semantics
 * as verify: a wrong code increments the counter, the 6th attempt or an expired/absent code
 * invalidates it), then, only on success, writes a fresh BCrypt hash and revokes every refresh-token
 * family the user owns so all existing sessions must re-login.
 *
 * <p>Every failure — unknown email, wrong/expired/absent code, attempt cap — throws the same
 * {@link InvalidOtpException} (one generic 400, no oracle). The new-password format (length, 72-byte
 * cap, not equal to the code) is validated at the web boundary before this service runs.
 */
public class ResetPasswordService implements ResetPassword {

	static final int MAX_ATTEMPTS = 5;

	private final UserRepository userRepository;
	private final OtpStore otpStore;
	private final PasswordHasher passwordHasher;
	private final RefreshTokenStore refreshTokenStore;

	public ResetPasswordService(UserRepository userRepository, OtpStore otpStore, PasswordHasher passwordHasher,
			RefreshTokenStore refreshTokenStore) {
		this.userRepository = userRepository;
		this.otpStore = otpStore;
		this.passwordHasher = passwordHasher;
		this.refreshTokenStore = refreshTokenStore;
	}

	@Override
	public void reset(String email, String code, String newPassword) {
		UserAccount account = userRepository.findByEmail(email).orElseThrow(InvalidOtpException::new);

		OtpVerificationResult result = otpStore.verify(OtpPurpose.RESET, account.id(), code, MAX_ATTEMPTS);
		if (result != OtpVerificationResult.SUCCESS) {
			throw new InvalidOtpException();
		}

		userRepository.updatePasswordHash(account.id(), passwordHasher.hash(newPassword));
		refreshTokenStore.revokeAllFamilies(account.id());
	}
}
