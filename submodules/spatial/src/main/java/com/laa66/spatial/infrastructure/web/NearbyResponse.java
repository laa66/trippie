package com.laa66.spatial.infrastructure.web;

import java.util.List;

import com.laa66.spatial.domain.model.NearbyResult;

/** Wire shape for the {@code /locations/nearby} response body. Items stay distance-ascending. */
record NearbyResponse(List<NearbyItem> items, boolean truncated) {

	static NearbyResponse from(NearbyResult result) {
		return new NearbyResponse(result.items().stream().map(NearbyItem::from).toList(), result.truncated());
	}
}
