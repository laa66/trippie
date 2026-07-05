# trippie

Location-aware mobile app that discovers points of interest (artworks, monuments, landmarks
sourced from OpenStreetMap) around the user and shows them on a live map. The long-term goal
is an AI-generated audio guide: for a selected POI the system produces a narrated description
(text + TTS audio) that the user can listen to on the spot.

> Status: early stage. The map + nearby-search slice works end to end. Auth, content
> generation and audio delivery from `docs/trippie-arch.puml` are target architecture, not yet built.

## Modules

| Path              | Stack                          | Responsibility                                                                 |
|-------------------|--------------------------------|--------------------------------------------------------------------------------|
| `gui/`            | Vue 3 + Ionic + Leaflet + Vite | Mobile UI. Watches device GPS and renders nearby POIs on a map.                |
| `backend/spatial/`| Java 21 + Spring Boot + PostGIS| Spatial service (hexagonal). Nearby-POI queries via `ST_DWithin`.              |
| `backend/commons/`| Java                           | Shared backend code.                                                           |
| `spatial-loader/` | Go + pgx                       | One-shot CLI that ingests an OSM GeoJSON export into the `location_point` table.|
| `docs/`           | PlantUML                       | Target system architecture.                                                    |

## Data flow (current)

1. `spatial-loader` bulk-loads OSM features (GeoJSON) into PostGIS (`location_point`,
   GiST index on `geom`, extra columns + `tags` JSONB).
2. `gui` watches the device position and calls the spatial service with lat/lon/radius.
3. `spatial` runs `ST_DWithin` (geography) ordered by distance and returns matching points.
4. `gui` renders POI markers plus a pulsing user-location marker.

## API

Base path: `/api/v1/spatial`

| Method | Path                | Params                          | Notes                          |
|--------|---------------------|---------------------------------|--------------------------------|
| GET    | `/location`         | —                               | Returns all points. Test-only. |
| GET    | `/location/nearby`  | `longitude`, `latitude`, `radius` (meters) | Nearby points, distance-sorted. |

## Running locally

```bash
# 1. Bring up PostGIS + spatial service
make build-cache
make up-background        # runs docker compose + loads seed data

# 2. Load OSM data into PostGIS (needs spatial-loader/data.geojson)
make initialize-spatial-db

# 3. Frontend
cd gui && npm install && npm run dev
```

Postgres defaults (docker-compose): db `spatial_db`, user `user`, pass `pass`, port `5432`.

## Tests

```bash
cd backend && ./gradlew test    # unit + Testcontainers integration tests (spatial)
```
