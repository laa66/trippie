package com.laa66.auth.infrastructure.web;

import java.util.List;

import com.laa66.auth.domain.model.ContentMode;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /settings} payload. The mode binds straight to the {@link ContentMode} enum, so an
 * unknown string already fails Jackson deserialization ({@code HttpMessageNotReadableException} ->
 * 400 via the inherited handler). {@link CategorySlugs} rejects any slug outside the eight-slug
 * contract; an empty list is valid.
 */
record UpdateSettingsRequest(
		@NotNull ContentMode defaultContentMode,
		@NotNull @CategorySlugs List<String> selectedCategories) {
}
