package com.laa66.spatial.domain.port.in;

import java.util.UUID;

/**
 * Inbound port: one POI's unlock state for one user — the single-id read the content service needs
 * before it serves or generates a guide (M4-04).
 *
 * <p>A read-only view over the same {@code poi_unlock} rows {@link UnlockLocations} writes. It is a
 * separate port because it is a separate use case: nothing here consults a position, a radius or the
 * proximity preference, so there is no policy to bypass and no way for this signature to grant
 * anything. The write stays behind {@link UnlockLocations} alone.
 *
 * <p><strong>An unknown {@code osmId} is {@code false}, not an error.</strong> "Not unlocked" is a
 * true statement about a POI that does not exist, the caller's next step is the same either way, and
 * the alternative would make this endpoint an existence oracle whose answers differ from the detail
 * endpoint's for no benefit.
 */
public interface CheckLocationUnlocked {

	boolean isUnlocked(UUID userId, String osmId);
}
