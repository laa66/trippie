.PHONY: build up down tiles

COMPOSE := docker compose -f infra/docker-compose.yml
MBTILES := infra/tiles/wroclaw.mbtiles

build:
	$(COMPOSE) build

# Bring up the default M1 backend stack (postgres, tileserver-gl, spatial,
# gateway, gui) detached. redis/rabbitmq stay behind `--profile infra`.
# Guard: the mbtiles must already exist as a FILE. If it is missing, Docker would
# create a root-owned directory at the bind-mount path and tileserver-gl would
# crash-loop — fail fast instead and point at `make tiles`.
up:
	@test -f "$(MBTILES)" || { \
		echo "ERROR: $(MBTILES) missing (or not a file). Run \`make tiles\` first."; \
		exit 1; \
	}
	$(COMPOSE) up -d

# -v also removes the named volumes (postgres-data, rabbitmq-data). Top-level
# volumes are removed regardless of which profile (if any) their service is in.
down:
	$(COMPOSE) down -v

# --- M0-06: self-hosted tiles seed --------------------------------------------
# Generate infra/tiles/wroclaw.mbtiles with Planetiler (basemap profile ->
# OpenMapTiles schema) from the Geofabrik dolnoslaskie extract, cropped to a
# Wroclaw bbox. Output + downloaded sources (.pbf, natural earth, water) are
# gitignored. Rerunnable: --force overwrites, cached sources are reused.
# --user keeps Planetiler artifacts host-owned (not root:root) so `make down`
# and manual cleanup work without sudo.
TILES_DIR      := infra/tiles
WROCLAW_BBOX   := 16.80,50.94,17.18,51.21
PLANETILER_IMG := ghcr.io/onthegomap/planetiler:0.9.0

tiles:
	mkdir -p $(TILES_DIR)
	docker run --rm \
		--user $(shell id -u):$(shell id -g) \
		-v "$(CURDIR)/$(TILES_DIR)":/data \
		$(PLANETILER_IMG) \
		--download \
		--area=dolnoslaskie \
		--bounds=$(WROCLAW_BBOX) \
		--output=/data/wroclaw.mbtiles \
		--http-timeout=120s \
		--http-retries=3 \
		--force
