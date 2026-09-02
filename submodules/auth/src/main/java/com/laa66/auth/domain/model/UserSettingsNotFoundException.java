package com.laa66.auth.domain.model;

/**
 * Raised when the settings row for a caller's user id does not exist. Unreachable with a valid
 * access token in practice (settings are seeded at signup, flow 06 assumes the caller is already
 * verified), but defended against a deleted-user race. Maps to a 404.
 */
public class UserSettingsNotFoundException extends RuntimeException {

	public UserSettingsNotFoundException() {
		super("settings not found");
	}
}
