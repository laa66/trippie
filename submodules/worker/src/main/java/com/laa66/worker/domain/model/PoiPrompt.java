package com.laa66.worker.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Everything about a POI the provider is allowed to see. The fields are the allowlist: spatial's
 * {@code description} has no field here, and of the raw OSM tags only the {@link PoiFact} set does,
 * so nothing else can reach the prompt by accident.
 *
 * <p>{@code name} and the fact values are free text from OSM mappers, so they are bounded and the
 * adapter passes them as data, never spliced into an instruction.
 */
public record PoiPrompt(String name, PoiCategory category, String lang, Map<PoiFact, String> facts) {

	/** Wrocław's longest real names are well under 100 characters; anything near this is not a name. */
	public static final int MAX_NAME_LENGTH = 200;

	/**
	 * OSM's own cap on a tag value, so a value from the map is never cut; the longest in Wrocław's
	 * data is a 253-character inscription (measured 2026-11-15). Spatial is another service, so the
	 * bound is applied here rather than trusted.
	 */
	public static final int MAX_FACT_LENGTH = 255;

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
		facts = bounded(facts);
	}

	private static Map<PoiFact, String> bounded(Map<PoiFact, String> facts) {
		Map<PoiFact, String> bounded = new EnumMap<>(PoiFact.class);
		facts.forEach((fact, value) -> {
			String stripped = value == null ? "" : value.strip();
			if (!stripped.isEmpty()) {
				bounded.put(fact, cut(stripped));
			}
		});
		return Collections.unmodifiableMap(bounded);
	}

	/** Cut on a code point, so a surrogate pair is never split into an invalid string. */
	private static String cut(String value) {
		if (value.codePointCount(0, value.length()) <= MAX_FACT_LENGTH) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, MAX_FACT_LENGTH)).strip();
	}
}
