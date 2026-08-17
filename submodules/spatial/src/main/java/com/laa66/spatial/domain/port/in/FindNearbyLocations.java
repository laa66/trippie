package com.laa66.spatial.domain.port.in;

import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;

/**
 * Inbound port: the use case the HTTP layer (M1-06) drives. Keeps the controller off the
 * outbound repository port so persistence stays swappable behind the application service.
 */
public interface FindNearbyLocations {

	NearbyResult find(NearbyQuery query);
}
