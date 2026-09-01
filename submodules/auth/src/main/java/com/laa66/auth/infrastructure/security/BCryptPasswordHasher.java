package com.laa66.auth.infrastructure.security;

import java.util.Map;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.laa66.auth.domain.port.out.PasswordHasher;

/**
 * BCrypt strength 12 behind a {@link DelegatingPasswordEncoder}, so every hash carries a
 * {@code {bcrypt}} prefix and the algorithm can be migrated later without a schema change. Only
 * spring-security-crypto is on the classpath — never the security starter (see build.gradle.kts).
 */
@Component
class BCryptPasswordHasher implements PasswordHasher {

	private static final int STRENGTH = 12;

	private final PasswordEncoder encoder = new DelegatingPasswordEncoder(
			"bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(STRENGTH)));

	@Override
	public String hash(String rawPassword) {
		return encoder.encode(rawPassword);
	}

	@Override
	public boolean verify(String rawPassword, String encodedHash) {
		return encoder.matches(rawPassword, encodedHash);
	}
}
