package com.laa66.worker.domain.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The OSM tags the prompt may carry, as a closed set (M4 input decision 7: an allowlist of fields,
 * not a dump of the JSONB). A tag with no constant here cannot reach the provider; {@link #from}
 * is the only place an OSM key is read.
 *
 * <p>Values stay in OSM notation ({@code C18}, {@code roman_catholic}): the prompt tells the model
 * so, which costs less than a translation table that would lag behind the map's vocabulary.
 */
public enum PoiFact {

	/** {@code addr:street} plus {@code addr:housenumber}; a number without a street says nothing. */
	ADDRESS,
	/** {@code start_date}, or {@code year_of_construction} when the first is absent. */
	START_DATE,
	ARCHITECT,
	ARTIST,
	HISTORIC,
	/** Presence only: OSM's values ({@code 2}, {@code yes}) are register levels, not facts. */
	HERITAGE,
	DENOMINATION,
	RELIGION,
	MEMORIAL,
	ARTWORK_TYPE,
	MATERIAL,
	INSCRIPTION,
	GERMAN_NAME,
	/** The article title of {@code wikipedia}, its language prefix ({@code pl:}) dropped. */
	WIKIPEDIA_TITLE;

	private static final Pattern WIKIPEDIA_LANG_PREFIX = Pattern.compile("^[a-z]{2,3}(-[a-z]+)*:");

	public static Map<PoiFact, String> from(Map<String, String> tags) {
		Map<PoiFact, String> facts = new EnumMap<>(PoiFact.class);
		put(facts, ADDRESS, address(tags));
		put(facts, START_DATE, present(tags.get("start_date")) ? tags.get("start_date") : tags.get("year_of_construction"));
		put(facts, ARCHITECT, tags.get("architect"));
		put(facts, ARTIST, tags.get("artist_name"));
		put(facts, HISTORIC, tags.get("historic"));
		put(facts, HERITAGE, "no".equals(tags.get("heritage")) ? null : tags.get("heritage"));
		put(facts, DENOMINATION, tags.get("denomination"));
		put(facts, RELIGION, tags.get("religion"));
		put(facts, MEMORIAL, tags.get("memorial"));
		put(facts, ARTWORK_TYPE, tags.get("artwork_type"));
		put(facts, MATERIAL, tags.get("material"));
		put(facts, INSCRIPTION, tags.get("inscription"));
		put(facts, GERMAN_NAME, tags.get("name:de"));
		String wikipedia = tags.get("wikipedia");
		put(facts, WIKIPEDIA_TITLE, wikipedia == null ? null : WIKIPEDIA_LANG_PREFIX.matcher(wikipedia).replaceFirst(""));
		return facts;
	}

	private static String address(Map<String, String> tags) {
		String street = tags.get("addr:street");
		if (!present(street)) {
			return null;
		}
		String number = tags.get("addr:housenumber");
		return present(number) ? street.strip() + " " + number.strip() : street;
	}

	private static void put(Map<PoiFact, String> facts, PoiFact fact, String value) {
		if (present(value)) {
			facts.put(fact, value);
		}
	}

	private static boolean present(String value) {
		return value != null && !value.isBlank();
	}
}
