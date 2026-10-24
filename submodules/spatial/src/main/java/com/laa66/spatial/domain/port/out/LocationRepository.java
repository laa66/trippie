package com.laa66.spatial.domain.port.out;

import java.util.Optional;

import com.laa66.spatial.domain.model.LocationDetail;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;

/**
 * Outbound port for reading POIs. The adapter is swappable in one class; the domain model,
 * this port and the service are untouched by the persistence choice. The query reaching this
 * port always carries a non-empty category set (the service resolved the "all" default).
 */
public interface LocationRepository {

	NearbyResult findNearby(NearbyQuery query);

	/**
	 * The by-id detail read: a primary-key lookup projecting the columns the nearby query leaves off.
	 *
	 * <p>Deliberately a second method on this port rather than a second port. It is the same table, the
	 * same adapter and the same ownership — and it is also what keeps the two reads visibly separate
	 * for {@code CacheableNearbyContractTest}, whose rule allows the cacheable layer to hold exactly
	 * this port: a new outbound port would have had to be argued against that allowlist for no gain.
	 *
	 * <p>{@link Optional#empty()} for an unknown id. A POI that is not in {@code location_point} is an
	 * ordinary outcome here — the id arrives from a URL — not an exceptional one.
	 */
	Optional<LocationDetail> findById(String osmId);
}
