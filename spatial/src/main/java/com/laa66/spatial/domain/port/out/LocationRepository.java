package com.laa66.spatial.domain.port.out;

import java.util.Collection;

import com.laa66.spatial.domain.model.LocationPoint;

public interface LocationRepository {
    Collection<LocationPoint> findAll();
}
