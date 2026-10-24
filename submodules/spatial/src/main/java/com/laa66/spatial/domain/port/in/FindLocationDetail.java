package com.laa66.spatial.domain.port.in;

import java.util.Optional;

import com.laa66.spatial.domain.model.LocationDetail;

/**
 * Inbound port: the by-id detail read the detail endpoint drives (M4-01), and through it the content
 * worker's prompt input (M4-11).
 *
 * <p><strong>No subject, on purpose.</strong> The signature carries no {@code UUID}, so there is no
 * way for this use case to become per-user by accident. Two things depend on that: the response is
 * cacheable as a pure function of {@code osmId}, and the worker — which has no user — can read a POI
 * without asserting an identity it does not have. Faking {@code X-User-Id} from the worker would be
 * the same class of bypass {@code ArchitectureTest} exists to block, so the honest route is a read
 * that never had a user in it. {@code LocationDetailControllerTest} pins the absence structurally.
 *
 * <p>An unknown id is {@link Optional#empty()} rather than an exception: "no such POI" is an
 * ordinary answer here, and the HTTP status it maps to belongs to the web layer.
 */
public interface FindLocationDetail {

	Optional<LocationDetail> find(String osmId);
}
