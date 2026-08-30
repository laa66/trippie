package com.laa66.auth.infrastructure.config;

import java.security.SecureRandom;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.auth.domain.port.in.RegisterUser;
import com.laa66.auth.domain.port.out.OtpMailer;
import com.laa66.auth.domain.port.out.OtpStore;
import com.laa66.auth.domain.port.out.PasswordHasher;
import com.laa66.auth.domain.port.out.UserRepository;
import com.laa66.auth.domain.service.RegistrationService;

/** Wires the framework-free domain services to their outbound adapters. */
@Configuration
class AuthConfig {

	@Bean
	SecureRandom secureRandom() {
		return new SecureRandom();
	}

	@Bean
	RegisterUser registerUser(UserRepository userRepository, PasswordHasher passwordHasher,
			OtpStore otpStore, OtpMailer otpMailer, SecureRandom secureRandom) {
		return new RegistrationService(userRepository, passwordHasher, otpStore, otpMailer, secureRandom);
	}
}
