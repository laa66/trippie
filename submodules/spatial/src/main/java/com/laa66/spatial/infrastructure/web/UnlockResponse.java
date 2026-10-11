package com.laa66.spatial.infrastructure.web;

import java.util.List;

import com.laa66.spatial.domain.model.UnlockResult;

/**
 * Wire shape for the {@code /locations/unlock} response body. {@code unlocked} carries the POIs that
 * are unlocked for this user afterwards, including ones that already were; {@code refused} means
 * strictly "too far away, or no such POI", with no reason codes — the accuracy case is a
 * whole-request failure (422), not a per-id one.
 */
record UnlockResponse(List<String> unlocked, List<String> refused) {

	static UnlockResponse from(UnlockResult result) {
		return new UnlockResponse(List.copyOf(result.unlocked()), List.copyOf(result.refused()));
	}
}
