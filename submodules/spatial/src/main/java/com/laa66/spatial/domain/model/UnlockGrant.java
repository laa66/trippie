package com.laa66.spatial.domain.model;

import java.util.Set;
import java.util.UUID;

/**
 * One batch of unlocks to persist: a set of POIs granted to one user from one source. Batch
 * rather than per-POI because the unlock contract is a batch contract — a single position claim
 * grants a whole cluster — and because it keeps the write at one prepared statement, one plan,
 * mirroring the {@code = ANY(?)} category filter on the read path.
 *
 * <p>{@code userId} is the opaque subject from {@code X-User-Id}; spatial does not resolve it.
 * Boundary validation of the raw request (ranges, caps, non-empty ids) belongs to the HTTP layer.
 */
public record UnlockGrant(UUID userId, Set<String> osmIds, UnlockSource source) {
}
