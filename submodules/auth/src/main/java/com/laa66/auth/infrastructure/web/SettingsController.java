package com.laa66.auth.infrastructure.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.auth.domain.model.UserSettingsData;
import com.laa66.auth.domain.port.in.GetUserSettings;
import com.laa66.auth.domain.port.in.UpdateUserSettings;

import jakarta.validation.Valid;

/**
 * Per-user settings endpoints (flow 06). The gateway strips {@code /api/auth}, so this maps
 * {@code /settings}. The caller's user id always comes from the gateway-set trusted
 * {@code X-User-Id} header, never from the body or path — the request body carries no user id
 * field, so a caller can structurally only ever read/write its own settings.
 */
@RestController
public class SettingsController {

	private final GetUserSettings getUserSettings;
	private final UpdateUserSettings updateUserSettings;

	public SettingsController(GetUserSettings getUserSettings, UpdateUserSettings updateUserSettings) {
		this.getUserSettings = getUserSettings;
		this.updateUserSettings = updateUserSettings;
	}

	@GetMapping("/settings")
	public SettingsResponse get(@RequestHeader("X-User-Id") UUID userId) {
		return toResponse(getUserSettings.get(userId));
	}

	@PutMapping("/settings")
	public SettingsResponse update(@RequestHeader("X-User-Id") UUID userId,
			@Valid @RequestBody UpdateSettingsRequest request) {
		UserSettingsData updated = updateUserSettings.update(
				userId, request.defaultContentMode(), request.selectedCategories());
		return toResponse(updated);
	}

	private static SettingsResponse toResponse(UserSettingsData data) {
		return new SettingsResponse(data.defaultContentMode().name(), data.selectedCategories());
	}
}
