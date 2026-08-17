package com.laa66.spatial.domain.port.out;

import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;

/**
 * Outbound port for reading POIs. The adapter is swappable in one class; the domain model,
 * this port and the service are untouched by the persistence choice. The query reaching this
 * port always carries a non-empty category set (the service resolved the "all" default).
 */
public interface LocationRepository {

	NearbyResult findNearby(NearbyQuery query);
}
