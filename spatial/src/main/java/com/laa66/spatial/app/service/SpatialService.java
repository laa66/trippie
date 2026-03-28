package com.laa66.spatial.app.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.domain.port.out.LocationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpatialService {

    private final LocationRepository locationRepository;

    public List<LocationPoint> findAllLocations() {
        log.info("SpatialService - enter find all locations");
        return (List<LocationPoint>) locationRepository.findAll();
    }

}
