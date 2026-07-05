.PHONY: build-cache up-background down initialize-spatial-db

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