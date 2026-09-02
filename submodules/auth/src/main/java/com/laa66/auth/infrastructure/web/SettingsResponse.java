package com.laa66.auth.infrastructure.web;

import java.util.List;

/** Response for both {@code GET /settings} and {@code PUT /settings} (flow 06). */
record SettingsResponse(String defaultContentMode, List<String> selectedCategories) {
}
