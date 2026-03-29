package com.laa66.spatial.app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SearchNearbyRequest {
    private double longitude;
    private double latitude;
    private double radius;
}
