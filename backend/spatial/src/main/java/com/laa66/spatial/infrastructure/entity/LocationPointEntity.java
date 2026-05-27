package com.laa66.spatial.infrastructure.entity;

import org.locationtech.jts.geom.Point;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "location_point")
@Data
@AllArgsConstructor
@NoArgsConstructor
public class LocationPointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "osm_id", nullable = false, unique = true)
    private String osmId;

    @Column(name = "geom", columnDefinition = "geometry(Point, 4326)")
    private Point coordinates;

    @Column(name = "name")
    private String name;

    @Column(name = "alt_name")
    private String altName;

    @Column(name = "official_name")
    private String officialName;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "name:en")
    private String nameEn;

    @Column(name = "name:pl")
    private String namePl;

    @Column(name = "description")
    private String description;

    @Column(name = "inscription")
    private String inscription;

    @Column(name = "artist_name")
    private String artistName;

    @Column(name = "artwork_type")
    private String artworkType;

}
