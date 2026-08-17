-- POIs ingested from OSM by the spatial-loader. Spatial owns this schema; the
-- postgis extension itself is cluster-level and comes from infra/init.
--
-- osm_id is both the public id and the primary key: the loader upserts on it and every
-- read projects it, so a surrogate serial would only add a second btree to maintain on
-- each upsert. Secondary indexes store TIDs, so a wider PK costs them nothing.
--
-- geography (not geometry): every read is a metric radius, so ST_DWithin uses the
-- plain GiST index directly instead of the geom::geography cast bypassing it.
-- Dedicated columns only for what is queried or projected; everything else -> tags.
CREATE TABLE location_point (
    osm_id      TEXT                   PRIMARY KEY,
    name        TEXT,
    description TEXT,
    category    TEXT                   NOT NULL,
    geom        geography(Point, 4326) NOT NULL,
    tags        JSONB                  NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMPTZ            NOT NULL DEFAULT now(),

    -- The DB is the enforcement point for the category contract shared by the Go
    -- loader, this service and the TS client; there is no shared codegen artifact.
    CONSTRAINT location_point_category_check CHECK (category IN (
        'public_art',
        'monuments',
        'heritage',
        'sacred',
        'museums',
        'viewpoints',
        'architecture',
        'attractions'
    ))
);

CREATE INDEX idx_location_point_geom ON location_point USING GIST (geom);
CREATE INDEX idx_location_point_category ON location_point (category);
