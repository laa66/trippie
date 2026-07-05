.PHONY: build-cache up-background down initialize-spatial-db tiles

build-cache:
	docker compose build

up-background:
	docker compose up -d
	initialize-spatial-db

down:
	docker compose down -v

initialize-spatial-db:
	cd spatial-loader && \
	go mod tidy && \
	go run main.go --dsn "postgres://user:pass@localhost:5432/spatial_db" --geojson data.geojson

# --- M0-06: self-hosted tiles seed --------------------------------------------
# Generate infra/tiles/wroclaw.mbtiles with Planetiler (basemap profile ->
# OpenMapTiles schema) from the Geofabrik dolnoslaskie extract, cropped to a
# Wroclaw bbox. Output + downloaded sources (.pbf, natural earth, water) are
# gitignored. Rerunnable: --force overwrites, cached sources are reused.
TILES_DIR      := infra/tiles
WROCLAW_BBOX   := 16.80,50.94,17.18,51.21
PLANETILER_IMG := ghcr.io/onthegomap/planetiler:0.9.0

tiles:
	mkdir -p $(TILES_DIR)
	docker run --rm \
		-v "$(CURDIR)/$(TILES_DIR)":/data \
		$(PLANETILER_IMG) \
		--download \
		--area=dolnoslaskie \
		--bounds=$(WROCLAW_BBOX) \
		--output=/data/wroclaw.mbtiles \
		--http-timeout=120s \
		--http-retries=3 \
		--force