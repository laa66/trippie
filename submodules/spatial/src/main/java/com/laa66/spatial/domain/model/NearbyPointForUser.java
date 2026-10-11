package com.laa66.spatial.domain.model;

/**
 * One nearby POI as one user sees it: the shared projection, plus that user's unlock state.
 *
 * <p>A wrapper rather than an {@code unlocked} field on {@link LocationPoint}, and that is the whole
 * point of this type. {@link LocationPoint} is what the user-free, cacheable
 * {@link com.laa66.spatial.domain.port.in.FindNearbyLocations} returns, and the M6 Redis cache keys
 * that result by {@code (position, radius, categories)} with <strong>no user dimension</strong>; an
 * {@code unlocked} field inside it would therefore be cached under a key that cannot tell users
 * apart and served from one user to the next. Keeping the flag outside makes that impossible by
 * construction instead of by remembering.
 */
public record NearbyPointForUser(LocationPoint point, boolean unlocked) {
}
