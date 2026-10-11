package com.laa66.auth.domain.model;

import java.util.List;

/** Per-user settings (flow 06): content mode, selected POI category slugs, and the proximity-unlock flag. */
public record UserSettingsData(ContentMode defaultContentMode, List<String> selectedCategories,
		boolean requireProximity) {
}
