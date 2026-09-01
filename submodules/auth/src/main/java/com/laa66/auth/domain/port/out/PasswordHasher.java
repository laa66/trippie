package com.laa66.auth.domain.port.out;

/** Outbound port for one-way password hashing, keeping the crypto library out of the domain. */
public interface PasswordHasher {

	String hash(String rawPassword);

	/** Constant-time-ish BCrypt comparison; false when the password does not match the hash. */
	boolean verify(String rawPassword, String encodedHash);
}
