package com.laa66.worker.domain.model;

/**
 * The eight category slugs spatial serves on the POI detail, each with the Polish phrase the prompt
 * uses. A slug this enum lacks is a bad detail, not a ninth category to pass through.
 */
public enum PoiCategory {

	PUBLIC_ART("public_art", "sztuka w przestrzeni publicznej"),
	MONUMENTS("monuments", "pomnik"),
	HERITAGE("heritage", "obiekt zabytkowy"),
	SACRED("sacred", "obiekt sakralny"),
	MUSEUMS("museums", "muzeum"),
	VIEWPOINTS("viewpoints", "punkt widokowy"),
	ARCHITECTURE("architecture", "obiekt architektury"),
	ATTRACTIONS("attractions", "atrakcja turystyczna");

	private final String slug;

	private final String polishLabel;

	PoiCategory(String slug, String polishLabel) {
		this.slug = slug;
		this.polishLabel = polishLabel;
	}

	public String slug() {
		return slug;
	}

	public String polishLabel() {
		return polishLabel;
	}

	public static PoiCategory fromSlug(String slug) {
		for (PoiCategory category : values()) {
			if (category.slug.equals(slug)) {
				return category;
			}
		}
		throw new IllegalArgumentException("unknown POI category slug: " + slug);
	}
}
