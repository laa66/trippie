package com.laa66.spatial.domain.model;

import java.util.Set;
import java.util.UUID;

/**
 * One batch of unlocks to attempt for one user, from one asserted position. Batch rather than
 * per-POI because the unlock contract is a batch contract — a single position claim grants a whole
 * cluster, and N separate claims would let the position move between them — and because it keeps
 * the write at one prepared statement, one plan.
 *
 * <p>{@code userId} is the opaque subject from {@code X-User-Id}; spatial does not resolve it.
 * Boundary validation of the raw request (ranges, caps, non-blank ids, de-duplication) belongs to
 * the HTTP layer; {@code radiusMeters} is unlock policy and belongs to the application service.
 *
 * <p>{@code source} is both the value persisted as {@code granted_by} and the switch that decides
 * whether the distance predicate is applied at all: {@code EXEMPT} means the user turned the
 * proximity requirement off, so {@code lat}/{@code lon}/{@code radiusMeters} are carried but not
 * consulted. They are not made optional for that case — the position is still what the client
 * asserted, and a shape that varies by source would need two code paths where the SQL needs one.
 */
public record UnlockGrant(
		UUID userId,
		double lat,
		double lon,
		int radiusMeters,
		Set<String> osmIds,
		UnlockSource source) {
}
