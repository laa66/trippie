package com.laa66.auth.domain.service;

import java.util.List;
import java.util.UUID;

import com.laa66.auth.domain.model.ContentMode;
import com.laa66.auth.domain.model.UserSettingsData;
import com.laa66.auth.domain.model.UserSettingsNotFoundException;
import com.laa66.auth.domain.port.in.GetUserSettings;
import com.laa66.auth.domain.port.in.UpdateUserSettings;
import com.laa66.auth.domain.port.out.UserRepository;

/**
 * Per-user settings use case (flow 06), framework-free and wired via {@code AuthConfig}. The
 * caller's user id is trusted as-is (it comes from the gateway-set {@code X-User-Id} header at the
 * web boundary), so both operations act on exactly that user's row.
 */
public class UserSettingsService implements GetUserSettings, UpdateUserSettings {

	private final UserRepository userRepository;

	public UserSettingsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserSettingsData get(UUID userId) {
		return userRepository.findSettings(userId).orElseThrow(UserSettingsNotFoundException::new);
	}

	@Override
	public UserSettingsData update(UUID userId, ContentMode mode, List<String> categories) {
		userRepository.updateSettings(userId, mode, categories);
		return new UserSettingsData(mode, categories);
	}
}
