package com.laa66.spatial.infrastructure.web;

import java.util.Map;

import com.laa66.spatial.domain.model.LocationDetail;

/**
 * Wire shape for the by-id detail read: {@code { id, name, category, description, tags }}.
 *
 * <p>{@code id} is the full {@code osm_id} ({@code "node/42"}) and {@code category} is the slug, the
 * same two conventions {@link NearbyItem} follows — a client holding a nearby item can use its
 * {@code id} here unchanged. The two shapes are otherwise disjoint on purpose: {@code description}
 * and {@code tags} exist only here, {@code distanceMeters} and {@code unlocked} only there. There is
 * no {@code unlocked} on this response: it is user-free, so the only honest value would be a lie.
 *
 * <p>{@code name} and {@code description} are serialised as {@code null} when the column is null,
 * rather than omitted, so the key set does not depend on the data.
 */
record LocationDetailResponse(
		String id,
		String name,
		String category,
		String description,
		Map<String, String> tags) {

	static LocationDetailResponse from(LocationDetail detail) {
		return new LocationDetailResponse(
				detail.osmId(),
				detail.name(),
				detail.category().slug(),
				detail.description(),
				detail.tags());
	}
}
