package com.laa66.spatial.domain.model;

import java.util.Set;
import java.util.UUID;

/**
 * Typed arguments for one unlock attempt: a subject, one asserted position with its measured
 * accuracy, and the POIs that position is claimed to reach. Already validated for shape by the HTTP
 * boundary (present, finite, in range, non-empty, capped, de-duplicated) and trusted from here on.
 *
 * <p>{@code accuracyMeters} is part of the contract rather than a hint. A fallback position has no
 * measured accuracy, so there is nothing to put in this field — which is what turns the client-side
 * invariant "never unlock from a fallback" into something the server can enforce. The threshold it
 * is compared against is policy and lives in the application service.
 */
public record UnlockAttempt(UUID userId, double lat, double lon, double accuracyMeters, Set<String> osmIds) {
}
