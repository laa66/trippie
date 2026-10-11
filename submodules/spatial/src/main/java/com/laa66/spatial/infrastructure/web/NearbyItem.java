package com.laa66.spatial.infrastructure.web;

import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.model.NearbyPointForUser;

/**
 * Wire shape for a single result item. {@code id} is {@link LocationPoint#osmId()} and
 * {@code category} is the slug ({@link com.laa66.spatial.domain.model.Category#slug()}), not the
 * enum name — the frozen REST contract has no {@code description}/{@code tags}.
 *
 * <p>{@code unlocked} is this caller's unlock state, decorated onto the shared projection outside
 * the cacheable layer (see {@link com.laa66.spatial.domain.port.in.FindNearbyLocationsForUser}).
 * It is per-user, so this record is built from {@link NearbyPointForUser} and never from a
 * {@link LocationPoint} alone — there is no {@code from(LocationPoint)} overload that could default
 * it to {@code false} and quietly lock everything.
 */
record NearbyItem(
		String id,
		String name,
		String category,
		double lat,
		double lon,
		double distanceMeters,
		boolean unlocked) {

	static NearbyItem from(NearbyPointForUser item) {
		LocationPoint point = item.point();
		return new NearbyItem(
				point.osmId(),
				point.name(),
				point.category().slug(),
				point.lat(),
				point.lon(),
				point.distanceMeters(),
				item.unlocked());
	}
}
