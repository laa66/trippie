package com.laa66.auth.domain.service;

import java.util.List;
import java.util.UUID;

import com.laa66.auth.domain.model.CategorySlugs;
import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.RegistrationCommand;
import com.laa66.auth.domain.port.in.RegisterUser;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Sign-up use case (flow 01), framework-free and wired via {@code AuthConfig}. Creates an
 * unverified account with a BCrypt-hashed password, seeds settings (mode BOTH + all eight
 * categories), then issues a fresh verification OTP through {@link OtpIssuer} (the same
 * issuance path resend uses). The raw password is never logged, returned, or persisted in
 * plaintext.
 */
public class RegistrationService implements RegisterUser {

	static final ContentMode DEFAULT_MODE = ContentMode.BOTH;

	static final List<String> DEFAULT_CATEGORIES = CategorySlugs.ALL;

	private final UserRepository userRepository;
	private final PasswordHasher passwordHasher;
	private final OtpIssuer issuer;

	public RegistrationService(UserRepository userRepository, PasswordHasher passwordHasher,
			OtpIssuer issuer) {
		this.userRepository = userRepository;
		this.passwordHasher = passwordHasher;
		this.issuer = issuer;
	}

	@Override
	public UUID register(RegistrationCommand command) {
		String email = command.email();
		if (userRepository.existsByEmail(email)) {
			throw new EmailAlreadyExistsException();
		}

		String passwordHash = passwordHasher.hash(command.rawPassword());
		UUID userId = userRepository.create(email, passwordHash, DEFAULT_MODE, DEFAULT_CATEGORIES);

		issuer.issue(OtpPurpose.VERIFY, userId, email);

		return userId;
	}
}
