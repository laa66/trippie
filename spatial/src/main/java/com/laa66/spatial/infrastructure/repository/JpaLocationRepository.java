package com.laa66.spatial.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.laa66.spatial.infrastructure.entity.LocationPointEntity;

public interface JpaLocationRepository extends JpaRepository<LocationPointEntity, Long> {

    @Query(value = """
            SELECT * FROM location_point
            WHERE ST_DWithin(
                coordinates::geography,
                ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                :radiusMeters
            )
            ORDER BY ST_Distance(coordinates::geography, ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography
            ) ASC
            """, nativeQuery = true)
    List<LocationPointEntity> findNearby(@Param("longitude") double longitude, @Param("latitude") double latitude,
            @Param("radiusMeters") double radiusMeters);

}
