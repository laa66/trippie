# Multi-stage build for the gui SPA.
# Stage 1: build the static bundle with Vite. VITE_TILES_URL is left empty so the
# map style is fetched origin-relative ( /styles/... ) and served by this same
# nginx via reverse proxy — a single browser-facing origin (localhost:8080).
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

ARG VITE_TILES_URL=""
ENV VITE_TILES_URL=${VITE_TILES_URL}
RUN npm run build

# Stage 2: nginx serving the bundle + reverse-proxying /api, /health and the
# tileserver-gl asset paths. curl is added for the compose healthcheck.
FROM nginx:alpine
RUN apk add --no-cache curl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
