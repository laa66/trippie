package com.laa66.spatial.domain.model;

/**
 * A single POI as the nearby query projects it: lean by design (no description, no tags — those
 * stay off this path and arrive on the by-id detail endpoint in M4). The public id is {@code osmId}.
 * {@code distanceMeters} is {@code ST_Distance} on the WGS84 spheroid — the same formula the query
 * sorts by, so the reported distance never desyncs from the ordering.
 */
public record LocationPoint(
		String osmId,
		String name,
		Category category,
		double lat,
		double lon,
		double distanceMeters) {
}
