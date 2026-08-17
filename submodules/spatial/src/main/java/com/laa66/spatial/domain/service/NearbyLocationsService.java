package com.laa66.spatial.domain.service;

import java.util.EnumSet;
import java.util.Set;

import com.laa66.spatial.domain.model.Category;
import com.laa66.spatial.domain.model.NearbyQuery;
import com.laa66.spatial.domain.model.NearbyResult;
import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.out.LocationRepository;

/**
 * Application service. Its one business rule is the "omitted categories = all eight" default,
 * so the repository always filters over a concrete, non-empty set (one prepared statement, one
 * plan). Framework-free by design — wired to Spring in {@code infrastructure.config}.
 */
public class NearbyLocationsService implements FindNearbyLocations {

	private final LocationRepository repository;

	public NearbyLocationsService(LocationRepository repository) {
		this.repository = repository;
	}

	@Override
	public NearbyResult find(NearbyQuery query) {
		Set<Category> categories = query.categories().isEmpty()
				? EnumSet.allOf(Category.class)
				: query.categories();

		return repository.findNearby(new NearbyQuery(
				query.lat(), query.lon(), query.radiusMeters(), categories, query.limit()));
	}
}
