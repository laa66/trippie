package com.laa66.spatial.infrastructure;

import java.util.Collection;

import org.springframework.stereotype.Component;

import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.port.out.LocationRepository;
import com.laa66.spatial.infrastructure.mapper.LocationEntityMapper;
import com.laa66.spatial.infrastructure.repository.JpaLocationRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PostgisLocationAdapter implements LocationRepository {

    private final JpaLocationRepository jpaLocationRepository;
    private final LocationEntityMapper locationEntityMapper;

    @Override
    public Collection<LocationPoint> findAll() {
        return jpaLocationRepository.findAll()
                .stream()
                .map(locationEntityMapper::toModel)
                .toList();
    }

    @Override
    public Collection<LocationPoint> findNearby(double longitude, double latitude, double radius) {
        return jpaLocationRepository.findNearby(longitude, latitude, radius)
                .stream()
                .map(locationEntityMapper::toModel)
                .toList();
    }

}
