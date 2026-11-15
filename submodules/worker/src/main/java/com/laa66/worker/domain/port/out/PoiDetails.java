package com.laa66.worker.domain.port.out;

import java.util.Optional;

import com.laa66.worker.domain.model.PoiDetail;
import com.laa66.worker.domain.model.TransientTaskException;

/** Outbound port: spatial's user-free POI detail. */
public interface PoiDetails {

	/**
	 * @return the detail, or empty when spatial does not know the POI
	 * @throws TransientTaskException when spatial gives no answer
	 */
	Optional<PoiDetail> find(String osmId);
}
