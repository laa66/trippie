# Multi-stage build for the spatial-loader one-shot CLI (M1-04). Runs via
# `docker compose run --rm` (tools profile) — never a long-running service.
FROM golang:1.26-alpine AS builder
WORKDIR /build

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 go build -o /spatial-loader .

# Runtime: no Go toolchain, no credentials. ca-certificates is required for the
# HTTPS call to the public overpass-api.de endpoint.
FROM alpine:3.20
RUN apk add --no-cache ca-certificates \
 && addgroup -S app && adduser -S -G app app
COPY --from=builder /spatial-loader /usr/local/bin/spatial-loader

USER app
ENTRYPOINT ["spatial-loader"]
