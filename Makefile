.PHONY: build-cache up-background down initialize-spatial-db

build-cache:
	docker compose build

up-background:
	docker compose up -d

down:
	docker compose down -v

initialize-spatial-db:
	cd spatial-loader && \
	psql "postgres://user:pass@localhost:5432/spatial_db" -f init_schema.sql && \
	go mod tidy && \
	go run main.go --dsn "postgres://user:pass@localhost:5432/spatial_db" --geojson wroclaw.geojson