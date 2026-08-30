package com.laa66.auth.domain.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.OtpPurpose;
import com.laa66.auth.domain.model.RegistrationCommand;
import com.laa66.auth.domain.port.in.RegisterUser;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.domain.port.out.OtpStore;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Sign-up use case (flow 01), framework-free and wired via {@code AuthConfig}. Creates an
 * unverified account with a BCrypt-hashed password, seeds settings (mode BOTH + all eight
 * categories), then issues a fresh verification OTP into the store and hands the raw code to the
 * mailer. The raw password is never logged, returned, or persisted in plaintext.
 */
public class RegistrationService implements RegisterUser {

	static final Duration OTP_TTL = Duration.ofMinutes(10);
	static final ContentMode DEFAULT_MODE = ContentMode.BOTH;

	// Auth's own copy of the 8-slug contract (like the DB CHECK and the loader/frontend lists);
	// there is no shared codegen artifact, the user_settings CHECK catches any drift.
	static final List<String> DEFAULT_CATEGORIES = List.of(
			"public_art", "monuments", "heritage", "sacred",
			"museums", "viewpoints", "architecture", "attractions");

	private static final int OTP_BOUND = 1_000_000;

	private final UserRepository userRepository;
	private final PasswordHasher passwordHasher;
	private final OtpStore otpStore;
	private final OtpMailer otpMailer;
	private final SecureRandom random;

	public RegistrationService(UserRepository userRepository, PasswordHasher passwordHasher,
			OtpStore otpStore, OtpMailer otpMailer, SecureRandom random) {
		this.userRepository = userRepository;
		this.passwordHasher = passwordHasher;
		this.otpStore = otpStore;
		this.otpMailer = otpMailer;
		this.random = random;
	}

	@Override
	public UUID register(RegistrationCommand command) {
		String email = command.email();
		if (userRepository.existsByEmail(email)) {
			throw new EmailAlreadyExistsException();
		}

		String passwordHash = passwordHasher.hash(command.rawPassword());
		UUID userId = userRepository.create(email, passwordHash, DEFAULT_MODE, DEFAULT_CATEGORIES);

		String code = generateOtp();
		otpStore.store(OtpPurpose.VERIFY, userId, code, OTP_TTL);
		otpMailer.send(OtpPurpose.VERIFY, email, code);

		return userId;
	}

	private String generateOtp() {
		return String.format("%06d", random.nextInt(OTP_BOUND));
	}
}
