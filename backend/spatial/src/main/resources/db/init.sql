CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS location_point (

    id            BIGSERIAL                PRIMARY KEY,
    osm_id        TEXT                  NOT NULL UNIQUE,
    geom          GEOMETRY(Point, 4326) NOT NULL,
    name          TEXT,               
    alt_name      TEXT,                 
    official_name TEXT,                 
    short_name    TEXT,                 
    "name:en"     TEXT,                 
    "name:pl"     TEXT,                 
    description   TEXT,
    inscription   TEXT,
    artist_name   TEXT,
    artwork_type  TEXT,
    tags          JSONB                 NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_location_point_geom
    ON location_point USING GIST (geom);


CREATE INDEX idx_location_point_artwork_type
    ON location_point (artwork_type)
    WHERE artwork_type IS NOT NULL;


CREATE INDEX idx_location_point_name
    ON location_point (name)
    WHERE name IS NOT NULL;

CREATE INDEX idx_location_point_tags_gin
    ON location_point USING GIN (tags);
