CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS wroclaw_features (

    id            SERIAL                PRIMARY KEY,
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

CREATE INDEX idx_wroclaw_geom
    ON wroclaw_features USING GIST (geom);


CREATE INDEX idx_wroclaw_artwork_type
    ON wroclaw_features (artwork_type)
    WHERE artwork_type IS NOT NULL;


CREATE INDEX idx_wroclaw_name
    ON wroclaw_features (name)
    WHERE name IS NOT NULL;

CREATE INDEX idx_wroclaw_tags_gin
    ON wroclaw_features USING GIN (tags);
