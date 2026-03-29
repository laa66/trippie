CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS location_point (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    coordinates geometry(Point, 4326)
);

CREATE INDEX IF NOT EXISTS idx_location_coordinates
ON location_point USING GIST (coordinates);