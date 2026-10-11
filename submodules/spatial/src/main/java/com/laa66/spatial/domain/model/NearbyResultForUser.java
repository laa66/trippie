package com.laa66.spatial.domain.model;

import java.util.List;

/**
 * A {@link NearbyResult} decorated with one user's unlock state. Order and {@code truncated} are
 * carried through from the undecorated result — decoration adds a field per item and changes nothing
 * else, so the cap and the distance-ascending ordering remain the cacheable layer's answer.
 */
public record NearbyResultForUser(List<NearbyPointForUser> items, boolean truncated) {
}
