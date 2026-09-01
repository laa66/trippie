package com.laa66.auth.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** {@code email} is CITEXT, so these derived queries fold case at the DB level. */
interface AppUserJpaRepository extends JpaRepository<AppUser, UUID> {

	boolean existsByEmail(String email);

	Optional<AppUserView> findViewByEmail(String email);

	Optional<AppUserCredentialsView> findCredentialsByEmail(String email);

	@Modifying
	@Query("update AppUser a set a.emailVerified = true where a.id = :id")
	int markVerified(@Param("id") UUID id);

	/** Closed projection for the verify/resend lookup — no password hash ever leaves the DB layer. */
	interface AppUserView {

		UUID getId();

		String getEmail();

		boolean isEmailVerified();
	}

	/**
	 * Closed projection for login only: it DOES expose the BCrypt hash, which the login service
	 * needs to verify the password. The hash stays inside the persistence + domain-service layers
	 * (mapped to {@code UserCredentials}) and is never placed in a DTO or response.
	 */
	interface AppUserCredentialsView {

		UUID getId();

		String getPasswordHash();

		boolean isEmailVerified();
	}
}
