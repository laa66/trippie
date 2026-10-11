package com.laa66.spatial.domain.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Outcome of one unlock attempt, partitioning the requested ids.
 *
 * <p><strong>{@code unlocked} is state, not the history of this request.</strong> A POI that was
 * already unlocked before this call belongs here, so {@code refused} means strictly "too far away,
 * or no such POI". The detail panel shows state; {@code refused} on a POI that is in fact unlocked
 * would read as a failure.
 */
public record UnlockResult(Set<String> unlocked, Set<String> refused) {

	/**
	 * Partitions {@code requested} by membership in {@code unlocked}. {@code unlocked} is intersected
	 * rather than copied so that a repository returning anything outside the request cannot leak into
	 * the response.
	 */
	public static UnlockResult partition(Collection<String> requested, Set<String> unlocked) {
		Set<String> granted = new LinkedHashSet<>();
		Set<String> refused = new LinkedHashSet<>();
		for (String osmId : requested) {
			(unlocked.contains(osmId) ? granted : refused).add(osmId);
		}
		return new UnlockResult(granted, refused);
	}
}
