package com.laa66.auth.domain.model;

import java.util.List;

/** Per-user settings (flow 06): the default content mode plus the selected POI category slugs. */
public record UserSettingsData(ContentMode defaultContentMode, List<String> selectedCategories) {
}
