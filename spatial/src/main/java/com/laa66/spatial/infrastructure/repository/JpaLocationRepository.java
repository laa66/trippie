package com.laa66.spatial.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.laa66.spatial.infrastructure.entity.LocationPointEntity;

public interface JpaLocationRepository extends JpaRepository<LocationPointEntity, Long> {

}
