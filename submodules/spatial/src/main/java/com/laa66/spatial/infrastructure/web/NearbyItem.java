package com.laa66.spatial.infrastructure.web;

import com.laa66.spatial.domain.model.LocationPoint;

/**
 * Wire shape for a single result item. {@code id} is {@link LocationPoint#osmId()} and
 * {@code category} is the slug ({@link com.laa66.spatial.domain.model.Category#slug()}), not the
 * enum name — the frozen REST contract has no {@code description}/{@code tags}.
 */
record NearbyItem(String id, String name, String category, double lat, double lon, double distanceMeters) {

	static NearbyItem from(LocationPoint point) {
		return new NearbyItem(
				point.osmId(), point.name(), point.category().slug(), point.lat(), point.lon(), point.distanceMeters());
	}
}
