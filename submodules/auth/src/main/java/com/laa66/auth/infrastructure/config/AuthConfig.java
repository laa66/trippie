package com.laa66.auth.infrastructure.config;

import java.security.SecureRandom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.auth.domain.port.in.RegisterUser;
import com.laa66.auth.domain.port.in.ResendVerification;
import com.laa66.auth.domain.port.in.VerifyEmail;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.domain.port.out.OtpStore;
import com.laa66.auth.domain.port.out.OtpThrottle;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.UserRepository;
import com.laa66.auth.domain.service.OtpGenerator;
import com.laa66.auth.domain.service.RegistrationService;
import com.laa66.auth.domain.service.ResendVerificationService;
import com.laa66.auth.domain.service.VerificationOtpIssuer;
import com.laa66.auth.domain.service.VerifyEmailService;

/** Wires the framework-free domain services to their outbound adapters. */
@Configuration
class AuthConfig {

	@Bean
	SecureRandom secureRandom() {
		return new SecureRandom();
	}

	@Bean
	OtpGenerator otpGenerator(SecureRandom secureRandom) {
		return new OtpGenerator(secureRandom);
	}

	@Bean
	VerificationOtpIssuer verificationOtpIssuer(OtpGenerator otpGenerator, OtpStore otpStore, OtpMailer otpMailer) {
		return new VerificationOtpIssuer(otpGenerator, otpStore, otpMailer);
	}

	@Bean
	RegisterUser registerUser(UserRepository userRepository, PasswordHasher passwordHasher,
			VerificationOtpIssuer verificationOtpIssuer) {
		return new RegistrationService(userRepository, passwordHasher, verificationOtpIssuer);
	}

	@Bean
	VerifyEmail verifyEmail(UserRepository userRepository, OtpStore otpStore) {
		return new VerifyEmailService(userRepository, otpStore);
	}

	@Bean
	ResendVerification resendVerification(UserRepository userRepository, OtpThrottle otpThrottle,
			VerificationOtpIssuer verificationOtpIssuer) {
		return new ResendVerificationService(userRepository, otpThrottle, verificationOtpIssuer);
	}
}
