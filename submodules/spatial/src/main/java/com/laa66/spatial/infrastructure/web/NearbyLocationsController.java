package com.laa66.spatial.infrastructure.web;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;

/**
 * Inbound HTTP adapter for the nearby use case. Drives {@link FindNearbyLocations} only — no
 * repository/service access — and owns all boundary validation of the raw query params, since
 * {@link NearbyQuery} is trusted from here on.
 */
@RestController
public class NearbyLocationsController {

	static final int DEFAULT_RADIUS_METERS = 1000;
	static final int MAX_RADIUS_METERS = 5000;
	static final int DEFAULT_LIMIT = 100;
	static final int MAX_LIMIT = 500;

	private final FindNearbyLocations findNearbyLocations;

	public NearbyLocationsController(FindNearbyLocations findNearbyLocations) {
		this.findNearbyLocations = findNearbyLocations;
	}

	@GetMapping("/locations/nearby")
	public NearbyResponse nearby(
			@RequestParam double lat,
			@RequestParam double lon,
			@RequestParam(required = false) Integer radius,
			@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String categories) {

		NearbyQuery query = toQuery(lat, lon, radius, limit, categories);
		return NearbyResponse.from(findNearbyLocations.find(query));
	}

	private NearbyQuery toQuery(double lat, double lon, Integer radius, Integer limit, String categories) {
		if (lat < -90 || lat > 90) {
			throw new InvalidRequestException("lat must be in [-90, 90]");
		}
		if (lon < -180 || lon > 180) {
			throw new InvalidRequestException("lon must be in [-180, 180]");
		}

		int radiusMeters = radius == null ? DEFAULT_RADIUS_METERS : radius;
		if (radiusMeters <= 0 || radiusMeters > MAX_RADIUS_METERS) {
			throw new InvalidRequestException("radius must be positive and at most " + MAX_RADIUS_METERS);
		}

		int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
		if (resolvedLimit <= 0 || resolvedLimit > MAX_LIMIT) {
			throw new InvalidRequestException("limit must be positive and at most " + MAX_LIMIT);
		}

		return new NearbyQuery(lat, lon, radiusMeters, toCategories(categories), resolvedLimit);
	}

	// Omitted/blank categories -> empty set, which NearbyLocationsService resolves to "all eight".
	// Lenient CSV: blank tokens (leading/trailing/doubled commas) are dropped rather than
	// rejected — only a non-blank, unknown slug is a 400 (user decision, M1-06 review).
	private Set<Category> toCategories(String categories) {
		if (categories == null || categories.isBlank()) {
			return Set.of();
		}
		try {
			return Arrays.stream(categories.split(","))
					.map(String::trim)
					.filter(token -> !token.isEmpty())
					.map(Category::fromSlug)
					.collect(Collectors.toUnmodifiableSet());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
	}
}
