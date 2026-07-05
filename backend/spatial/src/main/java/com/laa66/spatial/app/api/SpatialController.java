package com.laa66.spatial.app.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.app.dto.LocationPointDto;
import com.laa66.spatial.app.mapper.LocationDtoMapper;
import com.laa66.spatial.app.service.SpatialService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/location")
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
    public ResponseEntity<List<LocationPointDto>> findNearby(
            @RequestParam double longitude,
            @RequestParam double latitude,
            @RequestParam double radius) {
        log.info("SpatialController - enter find nearby with longitude={}, latitude={}, radius={}", longitude,
                latitude, radius);

        List<LocationPointDto> locationPoints = spatialService
                .findNearby(longitude, latitude, radius)
                .stream()
                .map(locationDtoMapper::toDto)
                .toList();
        log.info("SpatialController - found nearby location points: {}", locationPoints);
        return ResponseEntity.ok(locationPoints);
    }

}
