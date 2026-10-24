package com.laa66.spatial.domain.service;

import java.util.Optional;

import com.laa66.spatial.domain.model.LocationDetail;
import com.laa66.spatial.domain.port.in.FindLocationDetail;
import com.laa66.spatial.domain.port.out.LocationRepository;

/**
 * Application service for the by-id detail read. It has no business rule of its own — the lookup is
 * a primary-key read and the tag flattening is a property of the JSONB column, so it lives in the
 * adapter — and it exists for the reason the nearby service does: the controller drives an inbound
 * port and never an outbound one ({@code ArchitectureTest}). Framework-free by design; wired in
 * {@code infrastructure.config}.
 */
public class LocationDetailService implements FindLocationDetail {

	private final LocationRepository repository;

	public LocationDetailService(LocationRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<LocationDetail> find(String osmId) {
		return repository.findById(osmId);
	}
}
