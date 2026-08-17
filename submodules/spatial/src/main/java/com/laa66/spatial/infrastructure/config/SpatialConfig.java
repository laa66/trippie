package com.laa66.spatial.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.laa66.spatial.domain.port.in.FindNearbyLocations;
import com.laa66.spatial.domain.port.out.LocationRepository;
import com.laa66.spatial.domain.service.NearbyLocationsService;

/**
 * Wires the framework-free domain service to Spring, keeping the {@code @Configuration}
 * knowledge out of the domain package.
 */
@Configuration
public class SpatialConfig {

	@Bean
	FindNearbyLocations findNearbyLocations(LocationRepository locationRepository) {
		return new NearbyLocationsService(locationRepository);
	}
}
