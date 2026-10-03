.PHONY: build up down tiles load-pois auth-keys

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

# --- M1-04: one-shot POI ingest ------------------------------------------------
# Requires a running stack (`make up` first). tools-profile: never started by
# `make up`, only run explicitly here. bbox is the same rectangle as the tiles
# (single-sourced from WROCLAW_BBOX above); DATABASE_URL comes from the
# compose service's own environment.
load-pois:
	$(COMPOSE) run --rm spatial-loader --bbox $(WROCLAW_BBOX)

# --- M2-13: dev auth signing key -----------------------------------------------
# Generates a dev-only ES256 (EC P-256) signing key as a private JWK JSON, in the
# exact shape auth's JwtKeyLoader requires (kty/crv/x/y/d/kid). Mounted read-only
# into the auth container by infra/docker-compose.yml. Gitignored, never committed.
# Generated via the auth Gradle build's `genAuthKey` task (submodules/auth/build.gradle.kts),
# which reuses Nimbus's ECKeyGenerator — the same one JwtKeyLoaderTest uses — instead of a
# separate language/toolchain, so no new system dependency is introduced.
# Guarded twice: this target skips if the file already exists (cheap, avoids even starting
# Gradle), and the generator itself refuses to overwrite (atomic CREATE_NEW) if run directly.
AUTH_KEYS_DIR := infra/auth-keys
AUTH_JWK      := $(AUTH_KEYS_DIR)/dev-jwk.json

auth-keys:
	@mkdir -p $(AUTH_KEYS_DIR)
	@if [ -f "$(AUTH_JWK)" ]; then \
		echo "$(AUTH_JWK) already exists — leaving it in place (delete it first to regenerate)."; \
	else \
		( cd submodules/auth && ./gradlew -q genAuthKey -Pout="$(CURDIR)/$(AUTH_JWK)" ) && \
		chmod 644 "$(AUTH_JWK)" && \
		echo "Generated dev ES256 signing JWK at $(AUTH_JWK)"; \
	fi
	# ^ chmod 644, NOT 600/640: the auth container runs as a non-root user and reads this key off
	# a host-owned bind mount — a tighter mode would deny it read access and auth would fail to
	# boot. Do not "fix" this to 600.
