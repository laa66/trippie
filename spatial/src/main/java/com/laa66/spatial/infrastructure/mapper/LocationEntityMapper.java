package com.laa66.spatial.infrastructure.mapper;

import org.springframework.stereotype.Component;

import com.laa66.spatial.domain.model.LocationPoint;
import com.laa66.spatial.infrastructure.entity.LocationPointEntity;

@Component
public class LocationEntityMapper {

    public LocationPoint toModel(LocationPointEntity entity) {
        if (entity == null)
            return null;
        
        return new LocationPoint(entity.getName(), entity.getCoordinates().getX(), entity.getCoordinates().getY());
    }

}
