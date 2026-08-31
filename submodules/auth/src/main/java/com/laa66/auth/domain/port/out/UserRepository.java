package com.laa66.auth.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.UserAccount;

/** Outbound port for persisting accounts and their seeded settings. */
public interface UserRepository {

	boolean existsByEmail(String email);

	/** Resolves an account by (case-insensitive) email for the public verify/resend flows. */
	Optional<UserAccount> findByEmail(String email);

	/** Sets {@code email_verified = true}; idempotent if the account is already verified. */
	void markVerified(UUID userId);

	/**
	 * Inserts an unverified {@code app_user} and its seeded {@code user_settings} row in one
	 * transaction, returning the new user id. Implementations translate a unique-email collision
	 * (the check-then-insert race) into {@link EmailAlreadyExistsException}.
	 */
	UUID create(String email, String passwordHash, ContentMode defaultMode, List<String> selectedCategories);
}
