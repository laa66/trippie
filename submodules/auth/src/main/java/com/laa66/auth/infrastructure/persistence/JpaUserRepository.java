package com.laa66.auth.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.EmailAlreadyExistsException;
import com.laa66.auth.domain.model.UserAccount;
import com.laa66.auth.domain.port.out.UserRepository;

/** JPA-backed {@link UserRepository}: inserts the account and its seeded settings in one tx. */
@Component
class JpaUserRepository implements UserRepository {

	private final AppUserJpaRepository appUsers;
	private final UserSettingsJpaRepository userSettings;

	JpaUserRepository(AppUserJpaRepository appUsers, UserSettingsJpaRepository userSettings) {
		this.appUsers = appUsers;
		this.userSettings = userSettings;
	}

	@Override
	public boolean existsByEmail(String email) {
		return appUsers.existsByEmail(email);
	}

	@Override
	public Optional<UserAccount> findByEmail(String email) {
		return appUsers.findViewByEmail(email)
				.map(v -> new UserAccount(v.getId(), v.getEmail(), v.isEmailVerified()));
	}

	@Override
	@Transactional
	public void markVerified(UUID userId) {
		appUsers.markVerified(userId);
	}

	@Override
	@Transactional
	public UUID create(String email, String passwordHash, ContentMode defaultMode, List<String> selectedCategories) {
		UUID userId = UUID.randomUUID();

		// Only the app_user insert can violate the unique-email constraint, so only it is caught and
		// mapped to a 409 (closing the check-then-insert race). saveAndFlush surfaces the violation
		// here and guarantees app_user exists before the settings FK insert. A user_settings
		// violation (CHECK/FK/NOT NULL) is left to propagate as a real error, never masked as 409.
		try {
			appUsers.saveAndFlush(new AppUser(userId, email, passwordHash));
		}
		catch (DataIntegrityViolationException ex) {
			throw new EmailAlreadyExistsException();
		}

		userSettings.saveAndFlush(new UserSettings(
				userId, defaultMode.name(), selectedCategories.toArray(String[]::new)));
		return userId;
	}
}
