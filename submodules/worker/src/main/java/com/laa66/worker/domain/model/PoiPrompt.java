package com.laa66.worker.domain.model;

import java.util.Objects;

/**
 * Everything about a POI the provider is allowed to see. The fields are the allowlist: spatial's
 * {@code description} and raw OSM {@code tags} are mapper-written free text, i.e. a prompt injection
 * channel through reference data, so they have no field here and cannot reach the prompt by
 * accident.
 *
 * <p>{@code name} is free text from the same mappers and cannot be left out, so it is bounded and the
 * adapter passes it as data, never spliced into an instruction.
 */
public record PoiPrompt(String name, PoiCategory category, String lang) {

	/** Wrocław's longest real names are well under 100 characters; anything near this is not a name. */
	public static final int MAX_NAME_LENGTH = 200;

	/** The prompt is written in Polish, and content serves only {@code pl} (M4 input decision 6). */
	public static final String SUPPORTED_LANG = "pl";

	public PoiPrompt {
		Objects.requireNonNull(category, "category");
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("a POI without a name cannot be described");
		}
		name = name.strip();
		if (name.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException(
					"POI name is " + name.length() + " characters, the limit is " + MAX_NAME_LENGTH);
		}
		if (!SUPPORTED_LANG.equals(lang)) {
			throw new IllegalArgumentException("unsupported lang: " + lang);
		}
	}
}
