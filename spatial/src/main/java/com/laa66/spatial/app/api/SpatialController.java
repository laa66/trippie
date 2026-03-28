package com.laa66.spatial.app.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.app.dto.LocationPointDto;
import com.laa66.spatial.app.dto.SearchNearbyRequest;
import com.laa66.spatial.app.mapper.LocationDtoMapper;
import com.laa66.spatial.app.service.SpatialService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class SpatialController {

    private final SpatialService spatialService;
    private final LocationDtoMapper locationDtoMapper;

    // TODO: rid off this endpoint, only for testing purposes
    @GetMapping
    public ResponseEntity<List<LocationPointDto>> findAll() {
        log.info("SpatialController - enter findAll");
        List<LocationPointDto> locationPoints = spatialService.findAllLocations()
                .stream()
                .map(locationDtoMapper::toDto)
                .toList();
        log.info("SpatialController - found location points: {}", locationPoints);
        return ResponseEntity.ok(locationPoints);
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<LocationPointDto>> findNearby(@RequestBody SearchNearbyRequest request) {
        log.info("SpatialController - enter find nearby");

        List<LocationPointDto> locationPoints = spatialService
                .findNearby(request.getLongitude(), request.getLatitude(), request.getRadius())
                .stream()
                .map(locationDtoMapper::toDto)
                .toList();
        log.info("SpatialController - found nearby location points: {}", locationPoints);
        return ResponseEntity.ok(locationPoints);
    }

}
