package com.laa66.spatial.domain.model;

import java.util.List;

/**
 * Result of a nearby lookup: the items sorted distance-ascending and capped to the requested
 * limit, plus whether the cap actually truncated the full set.
 */
public record NearbyResult(List<LocationPoint> items, boolean truncated) {
}
