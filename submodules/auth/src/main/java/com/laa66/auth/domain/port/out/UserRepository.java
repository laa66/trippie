package com.laa66.auth.domain.port.out;

import java.util.List;
import java.util.UUID;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.EmailAlreadyExistsException;

/** Outbound port for persisting accounts and their seeded settings. */
public interface UserRepository {

	boolean existsByEmail(String email);

	/**
	 * Inserts an unverified {@code app_user} and its seeded {@code user_settings} row in one
	 * transaction, returning the new user id. Implementations translate a unique-email collision
	 * (the check-then-insert race) into {@link EmailAlreadyExistsException}.
	 */
	UUID create(String email, String passwordHash, ContentMode defaultMode, List<String> selectedCategories);
}
