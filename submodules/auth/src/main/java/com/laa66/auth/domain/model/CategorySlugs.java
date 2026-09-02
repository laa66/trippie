package com.laa66.auth.domain.model;

import java.util.List;
import java.util.Set;

/**
 * The eight-slug POI category contract, mirrored by the {@code user_settings_categories_check}
 * CHECK in {@code V1__auth.sql}. Single source of truth within auth: the signup seed
 * ({@link com.laa66.auth.domain.service.RegistrationService}) and the settings-update validator
 * both read from here instead of each hardcoding its own copy.
 */
public final class CategorySlugs {

	public static final List<String> ALL = List.of(
			"public_art", "monuments", "heritage", "sacred",
			"museums", "viewpoints", "architecture", "attractions");

	public static final Set<String> VALID = Set.copyOf(ALL);

	private CategorySlugs() {
	}
}
