package com.laa66.spatial.infrastructure.web;

import java.util.List;

/**
 * Wire shape of the {@code POST /locations/unlock} body: one asserted position with its measured
 * accuracy plus the POIs it is claimed to reach. The subject is not in the body — it arrives in the
 * {@code X-User-Id} header the gateway sets, which is the only place a caller cannot choose it.
 *
 * <p>Every field is boxed and {@code osmIds} is a {@code List}, both deliberately: an absent number
 * must arrive as {@code null} so the boundary can reject it, where a primitive would bind to
 * {@code 0.0} and turn a missing {@code accuracyMeters} into a perfect fix at the equator. A
 * {@code List} is what JSON actually is — it admits a repeated id, which the boundary de-duplicates
 * — and the element type is nullable, so {@code {"osmIds": [null]}} is rejected here rather than
 * reaching the insert as a {@code NOT NULL} violation.
 */
record UnlockRequest(Double lat, Double lon, Double accuracyMeters, List<String> osmIds) {
}
