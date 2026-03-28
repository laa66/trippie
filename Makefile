.PHONY: build-cache up-background down

build-cache:
	docker compose build

up-background:
	docker compose up -d

down:
	docker compose down -v