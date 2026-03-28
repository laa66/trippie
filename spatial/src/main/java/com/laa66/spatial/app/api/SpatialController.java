package com.laa66.spatial.app.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.laa66.spatial.infrastructure.entity.LocationPointEntity;
import com.laa66.spatial.infrastructure.repository.JpaLocationRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SpatialController {

    private final JpaLocationRepository jpaLocationRepository;


    @GetMapping
    public ResponseEntity<List<LocationPointEntity>> findAll() {
        List<LocationPointEntity> entities = jpaLocationRepository.findAll();
        System.out.println(entities);
        return ResponseEntity.ok(entities);
    }


}
