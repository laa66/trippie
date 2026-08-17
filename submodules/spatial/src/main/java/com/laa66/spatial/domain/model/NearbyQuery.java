package com.laa66.spatial.domain.model;

import java.util.Set;

/**
 * Typed arguments for a nearby lookup. Boundary validation of raw request params (ranges,
 * caps, unknown slugs) is the controller's job (M1-06); this value object is already-typed and
 * trusted. An empty {@code categories} set means "all categories" — the service resolves that
 * default before the query reaches the repository.
 */
public record NearbyQuery(
		double lat,
		double lon,
		int radiusMeters,
		Set<Category> categories,
		int limit) {
}
