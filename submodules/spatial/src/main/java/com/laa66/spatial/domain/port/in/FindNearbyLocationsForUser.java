package com.laa66.spatial.domain.port.in;

import java.util.UUID;

import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResultForUser;

/**
 * Inbound port the nearby endpoint drives: the geo lookup plus the caller's unlock state.
 *
 * <p><strong>Why this is a second port instead of a parameter on {@link FindNearbyLocations}.</strong>
 * M6 puts a Redis cache decorator on the geo lookup, keyed
 * {@code spatial:nearby:{geohash6}:{radiusBucket}:{sortedCategoryCsv}} — a key with no user
 * dimension, which is correct, because what it caches is a pure function of position, radius and
 * categories. Unlock state is not part of that function: it is per-user, it changes on a write path
 * that knows nothing about viewports, and folding it into the cached payload would serve one user's
 * unlocks to another — wrong and a privacy leak in the same mistake. So the composition is
 * deliberately this way round: {@link FindNearbyLocations} stays user-free and cacheable and M6's
 * decorator wraps <em>it</em>, while this port sits outside the cache and decorates its result.
 * The price is a second query instead of a join, knowingly paid.
 *
 * <p>{@code userId} is therefore a separate argument and not a field on {@link NearbyQuery}:
 * {@link NearbyQuery} is exactly the cache key's input, so the identity must not be able to reach
 * it. {@code CacheableNearbyContractTest} fails the build if either half of that drifts.
 */
public interface FindNearbyLocationsForUser {

	NearbyResultForUser find(UUID userId, NearbyQuery query);
}
