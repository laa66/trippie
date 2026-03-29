package com.laa66.spatial.app.mapper;

import org.springframework.stereotype.Component;

import com.laa66.spatial.app.dto.LocationPointDto;
import com.laa66.spatial.domain.model.LocationPoint;

@Component
public class LocationDtoMapper {

    public LocationPointDto toDto(LocationPoint model) {
        if (model == null)
            return null;

        return new LocationPointDto(model.name(), model.longitude(), model.latitude());
    }

}
