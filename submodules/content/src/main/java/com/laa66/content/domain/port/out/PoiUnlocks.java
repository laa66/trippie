package com.laa66.content.domain.port.out;

import java.util.UUID;

import com.laa66.content.domain.model.PoiUnlockStateUnavailableException;

/**
 * Outbound port for the one fact content needs from spatial: has this user unlocked this POI.
 *
 * <p><strong>A returned value is always an answer, never a degradation.</strong> {@code false} means
 * spatial said no, or the id cannot name a POI, so nothing could have been unlocked; both end in a
 * 403 (M4-08). "Don't know" has no return value: it is
 * {@link PoiUnlockStateUnavailableException}, which the read path (M4-06) and the generate path
 * (M4-08) both turn into a 503. Collapsing it to {@code false} would tell a user who is standing at
 * the POI to walk closer, because of an outage.
 *
 * <p>A bug in the implementation is not an outage and propagates as itself.
 */
public interface PoiUnlocks {

	/**
	 * @throws PoiUnlockStateUnavailableException when spatial gave no usable answer
	 */
	boolean isUnlocked(UUID userId, String osmId);
}
