# trippie

Location-aware app that turns the space around you into a self-guided tour. Discovers points
of interest (artworks, monuments, landmarks) from OpenStreetMap near your position, shows them
on a map, and (once unlocked by approach) generates AI-guided descriptions (text + narrated audio).

**Status: M0 skeleton.** The foundation is in place — a web app loads a map with self-hosted
tiles, requests your geolocation, and the gateway can route to backend services. No features
yet: POI discovery, auth, content generation, and audio playback are planned for M1–M5.
See `docs/ARCHITECTURE.md` for the target system, and `docs/flows/` for per-flow sequence diagrams.

## Modules

| Path                      | Stack                                  | Status                       |
|---------------------------|----------------------------------------|------------------------------|
| `submodules/gui`          | Vue 3 + Ionic 8 + Tailwind v4 + MapLibre GL | Web app shell; map loads, geolocation prompt works. No POI markers yet. |
| `submodules/spatial`      | Java 21 + Spring Boot                  | Stub; `/health` only. Controllers for nearby-POI queries (M1). |
| `submodules/gateway`      | Spring Cloud Gateway 5.0.0             | Routes `/health` → spatial, `/api/spatial/**` → spatial. Local JWT validation planned (M2). |
| `submodules/commons`      | Java (Gradle composite build)          | Shared backend utilities, consumed at source. |
| `submodules/spatial-loader` | Go                                   | Build-only. OSM→PostGIS ingestion skeleton (M1). |
| `submodules/auth`         | —                                      | Placeholder. Auth service + JWT scaffolded in M2. |
| `submodules/content`      | —                                      | Placeholder. Content generation service scaffolded in M4. |
| `submodules/worker`       | —                                      | Placeholder. Async content-generation worker scaffolded in M4. |
| `infra/`                  | Docker Compose + init SQL              | Postgres (PostGIS) + Redis + RabbitMQ behind `profiles:[infra]`; tileserver-gl. |

## Running locally

**Prerequisites:** Docker, `make`. The build is driven from the root `Makefile`; each
submodule is an independent build. There is no root `docker-compose.yml` — the stack lives in
`infra/docker-compose.yml` and `make` is the entry point.

```bash
# 1. Generate self-hosted tiles (run once, then reuse the cache)
make tiles
# Generates infra/tiles/wroclaw.mbtiles via Planetiler (basemap → OpenMapTiles schema)
# from the Geofabrik dolnośląskie extract, cropped to Wrocław. Gitignored; cached locally.

# 2. Bring up the stack (detached)
make up
# Services:
#  - gui (nginx SPA reverse-proxy): http://localhost:8080
#  - gateway, spatial, tileserver-gl: internal only (reachable via http://localhost:8080/api, etc.)
#  - data layer (postgres/redis/rabbitmq): off by default (see below)

# 3. Tear down
make down
```

**Single entry point:** The browser talks to **http://localhost:8080** only — the gui's nginx.
It serves the SPA and proxies:
- `/api` → gateway (which routes to services)
- `/health` → gateway (which routes to spatial)
- `/styles`, `/data`, `/fonts` → tileserver-gl (tile assets)

**Data layer (dev credentials):** Postgres, Redis and RabbitMQ sit behind `profiles: [infra]`
and are disabled by default — M0 has no DB consumers, and keeping them off avoids clashing with
a native Postgres on 5432. The `make` targets do not take compose flags, so start them directly:

```bash
docker compose -f infra/docker-compose.yml --profile infra up -d
```

They bind to loopback only. DEV-ONLY creds: user `trippie`, pass `trippie` (Postgres, RabbitMQ).
No production secrets here.

## Testing

Each module has its own test suite:

```bash
# Backend (Gradle)
cd submodules/commons && ./gradlew build     # commons
cd submodules/spatial && ./gradlew build     # spatial (unit + healthcheck)
cd submodules/gateway && ./gradlew build     # gateway (unit + integration)

# Frontend (Node + vitest)
cd submodules/gui && npm ci && npm test

# OSM ingestion CLI (Go) — skeleton, no tests yet
cd submodules/spatial-loader && go build ./... && go vet ./...
```

## What's next

M1 closes the discovery slice: OSM POIs render on the map, filtered by category, and distance-sorted.
`docs/ARCHITECTURE.md` records the MVP scope and the design decisions behind it.
