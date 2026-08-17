package com.laa66.spatial.domain.model;

/**
 * The eight POI category slugs. The DB CHECK on {@code location_point.category} is the
 * enforcement point for this contract (shared with the Go loader and the TS client); this
 * enum is the Java side of it, so the slugs must stay identical to that CHECK. Order matches
 * the loader's ordered first-match rule list.
 */
public enum Category {

	PUBLIC_ART("public_art"),
	MONUMENTS("monuments"),
	HERITAGE("heritage"),
	SACRED("sacred"),
	MUSEUMS("museums"),
	VIEWPOINTS("viewpoints"),
	ARCHITECTURE("architecture"),
	ATTRACTIONS("attractions");

	private final String slug;

	Category(String slug) {
		this.slug = slug;
	}

	public String slug() {
		return slug;
	}

	public static Category fromSlug(String slug) {
		for (Category category : values()) {
			if (category.slug.equals(slug)) {
				return category;
			}
		}
		throw new IllegalArgumentException("Unknown category slug: " + slug);
	}
}
