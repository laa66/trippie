package com.laa66.spatial.app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LocationPointDto {
    private String name;
    private double latitude;
    private double longitude;
}
