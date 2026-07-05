-- Runs once on first cluster init (docker-entrypoint-initdb.d), against the default
-- `postgres` database as the superuser. DB-per-service: one cluster, three logical DBs,
-- no shared tables. PostGIS is enabled ONLY on spatial_db.

CREATE DATABASE spatial_db;
CREATE DATABASE auth_db;
CREATE DATABASE content_db;

\connect spatial_db
CREATE EXTENSION IF NOT EXISTS postgis;
