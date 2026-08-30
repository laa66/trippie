package com.laa66.auth.domain.port.out;

/** Outbound port for one-way password hashing, keeping the crypto library out of the domain. */
public interface PasswordHasher {

	String hash(String rawPassword);
}
