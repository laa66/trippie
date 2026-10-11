# trippie — Architecture & MVP

Living document. It records the goal, the MVP scope, the agreed decisions (with rationale),
the components and the request flows. Visual companions: `trippie-arch.puml` (component
architecture) and `flows/` (one call-flow / sequence diagram per file, numbered 01–15).

## Goal

A location-aware app that turns the space around the user into a self-guided tour.
It discovers points of interest from OpenStreetMap near the user's position — across
**user-selectable categories** (public art, monuments, heritage, sacred, museums, viewpoints,
architecture, attractions) — shows them on a map, and lets the user unlock them by physically
approaching. Once unlocked, a POI can produce AI-generated content in the mode the user chose:
a written description (`TEXT`), a narrated recording (`AUDIO`), or both (`BOTH`).

### Interaction model

- POIs are visible on the map but **locked** until the user is within the unlock radius.
- Approaching a POI **unlocks** it; a locked POI cannot be generated.
- Tapping an unlocked POI generates content according to the active **content mode**.
- The default content mode lives in **user settings** (`TEXT` / `AUDIO` / `BOTH`), so a tap
  produces the desired output without an extra prompt. The user can still override per POI.
- The user picks which **POI categories** to display (user settings); the map and nearby queries
  only return the selected categories.

## MVP

The smallest slice that proves the full end-to-end loop:

1. User opens the **web app** and grants geolocation.
2. Map (**MapLibre GL** + self-hosted tiles) renders nearby POIs as **locked** (spatial service).
3. As the user moves, POIs within the unlock radius become **unlocked**.
4. User taps an unlocked POI. The request carries the active **content mode**
   (`TEXT` / `AUDIO` / `BOTH`), taken from user settings unless overridden.
5. **Content service** returns existing content for that mode if present; otherwise enqueues a
   generation task and reports `Processing`.
6. **Content worker** generates according to mode: text (Gemini) and, for `AUDIO`/`BOTH`,
   audio (ElevenLabs) → uploads to R2. It saves the text and/or audio link, marks `Completed`.
7. Frontend polls status, then shows the text and/or plays the audio.

Auth is part of the MVP (accounts + JWT + per-user default content mode).
Mobile packaging is **not** — see "Out of MVP".

## Decisions

| Topic | Decision | Rationale |
|-------|----------|-----------|
| Starting point | Full reset to zero | Clean slate; rebuild with the correct target architecture from the start. |
| Backend topology | Microservices from the start | gateway, auth, spatial, content, worker as separate services. |
| Database topology | DB-per-service; one Postgres cluster, separate logical DBs | Each service owns its data (`spatial_db`, `auth_db`, `content_db`). No shared tables. PostGIS enabled only on `spatial_db`. Splitting to separate instances later = connection-string change, no code change. |
| API gateway | Spring Cloud Gateway (reactive), behind K8s Ingress | App-level routing + local JWT validation + rate limiting. Routes to K8s Service DNS. |
| Service discovery / LB | Delegated to Kubernetes | K8s Service DNS + kube-proxy. **No** Eureka / Spring Cloud LoadBalancer / client-side LB. |
| Runtime concurrency | Java 21 virtual threads on servlet services | `spring.threads.virtual.enabled=true`. Blocking JDBC/HTTP at high concurrency with simple imperative code. Gateway stays reactive (no VT needed). |
| POI categories | User-selectable categories mapped to OSM tags | Users see friendly categories, not raw OSM tags. Category is derived at ingestion and stored on each POI. |
| POI schema | Generic model + derived `category` + `tags` JSONB | Broad category set makes artwork-specific columns sparse. Keep common columns (`name`, `description`, `geom`, `osm_id`, indexed `category`) + everything else in `tags`. |
| Frontend | Web-first (Vue 3 + Quasar + Tailwind v4) | Ship a working web app + full backend first; native comes last. Quasar supplies the components, Tailwind the layout; both read one token source. See "On styling". |
| Map engine | MapLibre GL | Modern vector renderer; aligns with self-hosted tiles. |
| Tiles | Self-hosted tile server (OSM mbtiles/pbf) | Full control, offline-friendly, no CDN limits. |
| Auth | In MVP — dedicated service + JWT | MVP has per-user data; gateway validates tokens. |
| Token strategy | Short-lived access JWT + rotating refresh token | Access token validated **locally at the gateway** (JWKS) — no per-request round-trip. Refresh token stored/rotated in Redis by auth. Optional access-token denylist in Redis for instant revocation. |
| POI unlocking | Proximity-based, within unlock radius | POIs are locked on the map until the user approaches; only unlocked POIs can generate. |
| Content modes | `TEXT` / `AUDIO` / `BOTH` | Not audio-only. User can read, listen, or both. Content is cached per POI **and mode**. |
| Default content mode | Per-user setting | A tap produces the chosen output without an extra prompt; overridable per POI. |
| LLM (text) | Gemini API | Chosen provider for narrative generation. Text is the base for every mode. |
| TTS (audio) | ElevenLabs | Narrated audio; generated from the text for `AUDIO`/`BOTH`. |
| Audio storage | Cloudflare R2 (S3-compatible) | Object storage for generated `.mp3`. |
| Cache & state | Redis | Spatial cache + generation status tracking. |
| Async / queue | RabbitMQ, behind a messaging port | Fits a simple task queue; migratable to Kafka if event replay/streaming is ever needed. |

### On the messaging port

The message broker is hidden behind a `EventPublisher` / `TaskConsumer` port (hexagonal).
Business logic never talks to the broker client directly. Switching RabbitMQ → Kafka later
means swapping the adapter + config, not rewriting services. Migrate only if replay,
partitioned ordering, or multiple independent consumers of the same stream become a real need.

### On auth & tokens

- **Login** hits the DB (password hash) — Redis does not speed this up; there is nothing to cache.
  Redis speeds up *per-request authorization* and enables *revocation*, not the login handshake.
- **Access token**: short-lived JWT (e.g. 15 min), signed asymmetrically. The gateway validates
  it **locally** using the auth service's public keys (JWKS) — no round-trip per request. This is
  the fast path.
- **Refresh token**: long-lived, opaque, stored in Redis by auth with **rotation** (each refresh
  invalidates the previous one). Enables revoking a session.
- **Revocation/logout**: because JWT is stateless, instant revocation of a still-valid access token
  needs a small `jti` **denylist** in Redis that the gateway checks. TTL = remaining token life.
  Optional for MVP — short access-token TTL already bounds the exposure.

### On styling (Quasar + Tailwind)

Components come from Quasar; layout, spacing and the remaining authored markup come from Tailwind.
Both read colour, shape and type from one token chain, so no component carries a colour or radius
literal. Plain CSS is the exception: the scoped `<style>` blocks are the MapLibre attribution skin in
`MapView.vue` (see "Map container") and a swatch in the dev-only gallery.

**Token chain**

1. `src/css/quasar.variables.scss`: brand palette, shape and input size as SASS variables. The only
   file where brand hex values are written.
2. `@quasar/vite-plugin` injects that file into every SASS compile (`sassVariables`, absolute path).
   Quasar emits the values at build time as `--q-*` custom properties (`--q-primary`, `--q-negative`, ...).
3. `src/theme.css`, `:root`: the **semantic layer**. Its custom properties name a role, not a colour:
   `--app-bg`, `--app-surface`, `--app-surface-sunken`, `--app-ink`, `--app-ink-muted`, `--app-line`,
   `--app-tint-{brand,warning,danger}`, `--app-shadow-{card,raised}`, and the `--app-poi-*` set.
4. `@theme` in `theme.css` maps Tailwind tokens onto (2) and (3): `--color-primary: var(--q-primary)`,
   `--color-surface: var(--app-surface)`, `--radius-card`, `--shadow-card`. Quasar's `$positive`,
   `$negative` and `$accent` feed Tailwind's `success`, `danger` and `tertiary`.
5. Components use utilities: `bg-surface`, `text-ink-muted`, `border-line`, `rounded-card`, `shadow-card`.

**Why two layers.** Dark mode is meant to be an addition, not a rewrite: a `body--dark` block will
redefine the semantic properties and no screen changes. It does not exist yet. For the same reason an
SFC must not contain a hex value or an arbitrary value such as `bg-[#...]`, because a literal cannot be
re-themed. The `--app-poi-*` set is also declared as literal values, since jsdom does not resolve
`var()` indirection in custom properties and a token pointing at another token would read back
differently in tests.

**Text colour.** `$secondary` is decorative and never carries text. `$warning` is a dark amber for
text; the bright amber exists only as the surface tint `--app-tint-warning`. Every text/background pair
is measured against WCAG AA before use, and a new colour is measured the same way.

**Typography.** Inter is self-hosted (`@fontsource-variable/inter`), since the app is one origin behind
nginx. The base size is 16px. `$input-font-size: 16px` must stay: Quasar's 14px default on `.q-field`
(a class) outranks the `body` element rule, and an input below 16px makes iOS Safari zoom on focus.

**Preflight stays off.** `theme.css` imports `tailwindcss/theme.css` and `utilities.css` only. Quasar
ships its own reset, so Tailwind's would duplicate it. One Quasar reset rule is overridden explicitly:
`p { margin: 0 0 16px }` would put 16px under every paragraph, so paragraph margin is zeroed once in
`@layer base`. Do not add `m-0` per element.

**Layers and import order.** `theme.css` declares `@layer theme, base, components, utilities`. Compiled
Quasar CSS (`src/css/quasar.scss`), MapLibre's stylesheet and the font go into `base`; Tailwind
utilities go into `utilities`. Layering is required because unlayered CSS beats every layer, and Quasar
uses the same class names as Tailwind (`.flex`, `.absolute`, `.hidden`, `.items-center`, ...). An
unlayered `.flex` also adds `flex-wrap: wrap`, which silently defeats `flex-nowrap`.

`main.ts` must import `./css/quasar.scss` before `./theme.css`. Both sit in `base`, and at equal
specificity the later rule wins, so the base block in `theme.css` overrides Quasar's `body` typography.

**Collision boundaries.** 79 class names exist in both Quasar and Tailwind. 56 of them are resolved by
layering. The other 23 carry `!important` in Quasar and cannot be overridden from Tailwind:
- `text-*` and `bg-*` for `dark primary secondary warning white black transparent`
- `cursor-pointer`, `cursor-not-allowed`, `cursor-none`
- `block`, `inline-block`, `hidden`, `invisible`, `overflow-auto`, `overflow-hidden`

Treat these as Quasar's. Their values match Tailwind's because both come from the same chain, but a
local override will not win.

Never use `rotate-*`, `col-*` or `order-*`. Quasar applies `transform: rotate` and a 12-column flex
grid; Tailwind sets different properties for the same effect, and both declarations apply and add up
(measured: 45° + 45°).

Quasar marks `QRadio`, `QToggle`, `QChip`, `QFab` and `QRating` with `row inline` / `column inline` /
`flex inline`. Its `display: inline-flex` rule sits in `base`, so Tailwind's `.inline` in `utilities`
wins by layer and collapses those controls to `display: inline`. `theme.css` re-asserts the rule in the
`utilities` layer, mirroring Quasar's selector rather than listing components.

**Safe area.** Quasar's `--q-safe-area-inset-*` properties are declared only under
`body.q-safe-area-padding` / `body.q-ios-padding`, which this app does not set. A `var()` on an
undefined custom property makes the whole declaration `unset`, not a fallback. Use
`env(safe-area-inset-*, 0px)` with an explicit fallback. The global `.q-header` rule in `theme.css`
applies it to every header.

**Map container.** The map uses `absolute inset-0`, not `h-full`. Quasar gives `q-page` only a
`min-height`, so a percentage height resolves to `auto` and the container collapses. The MapLibre
attribution control is skinned in a scoped `<style>` block in `MapView.vue`, using `:deep(.maplibregl-*)`
selectors only. Scoped styles are unlayered and would beat utilities if written on app classes, so
keep class selectors out of that block.

**Components in `src/components/ui/`.** Three wrappers; the budget of three is used up.
- `AppScreen`: shell of the five auth screens. Lockup above the card, screen title inside the card
  (`data-screen-title`), optional `footer` slot.
- `AppNotice`: every in-page message. Props `variant` (`danger` | `warning` | `info`) and `role`
  (`alert` | `status`, required). Colour is a tint behind dark ink, so the message does not depend on
  the icon or the hue for contrast.
- `AppLogo`: typographic "trippie" lockup. Replacing it with an image touches only this file.

Everything else uses Quasar components directly.

**Reference: `/style`.** Dev-only gallery (`import.meta.env.DEV`, dynamic import, absent from the
production bundle, verified). It shows tokens, type scale, button and field states, notices and the
POI palette. It is the reference for the system: a new pattern is added there, not to a separate design
document. Reachable only while signed out, as the `guestOnly` routes are.

**POI layer colours.** MapLibre takes paint values as style, not CSS. `src/lib/poiLayer.ts` therefore
holds only the names of the `--app-poi-*` properties. It reads their values once per map through
`getComputedStyle` and builds the colour expression. The palette itself lives in `theme.css` next to
every other colour, so a dark theme redefines it the same way.

**Dark mode.** Not implemented. The extension point is the semantic layer and the `--app-poi-*` set.

## Components

| Component | Stack | Responsibility |
|-----------|-------|----------------|
| Web app | Vue 3 + Quasar + Tailwind v4 + MapLibre GL | UI, geolocation, map with locked/unlocked POIs, proximity unlock, category + content-mode selection, text display + audio playback, settings, status polling. |
| Tile server | tileserver (OSM mbtiles/pbf) | Serves self-hosted vector tiles. |
| API Gateway | Spring Cloud Gateway | Single entry point; **local** access-JWT validation (JWKS from auth); denylist check; routing to services. |
| Auth service | Spring Boot | User accounts, access/refresh token issuance + rotation, JWKS, per-user settings (default content mode, selected POI categories). Owns `auth_db`. |
| Spatial service | Spring Boot + PostGIS (hexagonal) | Pure geo. Nearby-POI queries via `ST_DWithin`, filtered by selected categories, distance-sorted. Cached in Redis. Owns `spatial_db`. |
| Content service | Spring Boot | Orchestrates generation per mode (`TEXT`/`AUDIO`/`BOTH`); owns generation state; serves existing content keyed by POI + mode. Owns `content_db`. |
| Content worker | Spring Boot | Consumes tasks; generates text (Gemini) and, for `AUDIO`/`BOTH`, audio (ElevenLabs) → R2; persists results. |
| spatial-loader | Go + pgx | Offline **batch** job (dev + prod, **off the request path**). Transforms OSM GeoJSON into `location_point` (upsert by `osm_id`). Run on demand / scheduled, not a runtime service. |

## POI categories

Users select which categories to display. Each user-facing category maps to a set of OSM tags;
the ingestion step classifies every POI into exactly one category and stores it on the row.

| Category | OSM tags |
|----------|----------|
| Public art | `tourism=artwork`, `historic=statue` |
| Monuments & memorials | `historic=monument`, `historic=memorial` |
| Heritage & history | `historic=castle`, `ruins`, `archaeological_site`, `city_gate`, `tower`, `fort`, `manor`, `monastery`, `tomb`; `heritage=*` |
| Sacred | `amenity=place_of_worship`; `historic=wayside_shrine`, `wayside_cross` |
| Museums & galleries | `tourism=museum`, `gallery`; `amenity=arts_centre` |
| Viewpoints & nature | `tourism=viewpoint`; `natural=peak`, `waterfall`, `cave_entrance`; `leisure=park`, `garden` |
| Architecture | `man_made=tower`, `lighthouse`, `windmill`, `watermill`, `bridge`; buildings tagged `heritage` |
| Attractions (general) | `tourism=attraction` |

## POI data source

POIs come from **OpenStreetMap via the Overpass API**, filtered to the tags backing the
categories above, over the target area, exported as GeoJSON. Overpass emits the OSM id as `@id`,
which is exactly what `spatial-loader` keys on — so the export feeds the loader unchanged.
The loader derives each POI's `category` from its tags during ingestion.

- **Small areas (a city/region, MVP):** Overpass Turbo (manual) or the Overpass API (scripted).
- **Large areas:** a Geofabrik `.osm.pbf` extract filtered with `osmium`, to avoid Overpass limits.
- **Ingestion runs in dev and prod** as an offline batch job (on demand or scheduled); OSM POI
  data is slow-moving reference data, not user-generated.
- **Licensing:** OSM data is **ODbL** — attribution ("© OpenStreetMap contributors") is required;
  mind share-alike obligations.

## Data layer

One PostgreSQL cluster, one logical database per service (DB-per-service):

- **`spatial_db`** (PostgreSQL + **PostGIS**) — POIs (`location_point`, GiST on `geom`,
  indexed `category`). Owned by spatial service.
- **`auth_db`** (PostgreSQL) — users + per-user settings (default content mode, selected
  categories). Owned by auth. No PostGIS.
- **`content_db`** (PostgreSQL) — generated content metadata keyed by POI + mode (text, audio
  link). Owned by content service. No PostGIS.
- **Redis** — spatial query cache, generation status (`Processing` / `Completed`),
  refresh tokens (rotation) + optional access-token denylist (owned by auth).
- **Cloudflare R2** — generated audio files.

The content worker needs POI details (name, tags) to build the LLM prompt; it fetches them from
the **spatial service API**, not from `spatial_db` directly, keeping service boundaries clean.

## Request flows

Sequence diagrams for all cases live in `flows/` (one per file): sign-up, login, authenticated
request, token refresh, logout/revocation, settings update, discovery (cache hit/miss), proximity
unlock, content generation (cache hit, TEXT, AUDIO/BOTH, dedup, failure/retry), and ingestion.
Summaries below.

**Auth** — `Gateway → Auth`: login (DB check) issues access + refresh tokens; refresh (Redis,
rotation) mints a new pair. Every other request carries the access JWT, validated **locally** at
the gateway (JWKS) with a denylist check in Redis.

**Discovery** — `Gateway → Spatial` with position + selected categories: check Redis cache →
`ST_DWithin` filtered by category on miss → cache → return. POIs come back locked; the client
unlocks those within the unlock radius as the user moves.

**Generation** — `Gateway → Content` with a POI id + content mode: return existing content for
that POI + mode, or enqueue a task + set `Processing`. `Queue → Worker`: Gemini (text) and,
for `AUDIO`/`BOTH`, ElevenLabs (audio) → R2 (upload) → DB (save text and/or link) → Redis
(`Completed`). Frontend polls `Content` for status, then shows text and/or plays audio.

## Boundaries / responsibilities

- **Spatial service stays pure geo.** It does not orchestrate content generation.
  All generation concerns live in Content service + Content worker. (This corrects the earlier
  design where spatial owned the generation queue.)
- **Gateway is the only public entry point.** Services trust internal invariants; validation
  happens at the gateway (auth) and at service boundaries (input DTOs).

## Deployment & runtime (K8s-oriented)

**MVP runs locally on docker-compose; Kubernetes comes after the MVP** (web + full backend working).
The decisions below are K8s-oriented on purpose so the migration is manifests + config, not a
redesign: services address each other by **DNS names** — locally the compose service names
(`auth`, `spatial`, ...), later K8s Service names. Postgres/Redis/RabbitMQ run as compose
containers now, as Deployments/StatefulSets (or managed services) later. TLS/edge: the gateway is
exposed directly in compose; a K8s Ingress is added in front of it later.

Target is Kubernetes. The design avoids Spring-level infrastructure that duplicates the platform.

- **Discovery & load balancing → Kubernetes.** Gateway and services address each other by stable
  K8s Service DNS names; K8s Service + kube-proxy handle balancing. No Eureka, no Spring Cloud
  LoadBalancer, no client-side LB.
- **Edge → K8s Ingress** (nginx/Traefik or Gateway API): TLS termination + external exposure.
  **Spring Cloud Gateway** sits behind it for app-level routing, JWT validation, rate limiting.
- **Gateway is reactive** (Netty). Being non-blocking, it does not use virtual threads.
- **Services use virtual threads** (`spring.threads.virtual.enabled=true`): auth, spatial, content,
  worker are Spring MVC with blocking I/O (JDBC/PostGIS, external HTTP, RabbitMQ). Loom gives high
  concurrency with imperative code. Caveats: avoid `synchronized` around blocking I/O (use
  `ReentrantLock`) to prevent carrier-thread pinning; size HikariCP for the DB regardless.
- **Static routing config** points the gateway at cluster DNS; no dynamic registry.

## Out of MVP (LATER)

- Capacitor native wrapper (iOS/Android) with native GPS — added after web + backend are done.
- Notification service — transactional emails (account creation, email confirmation, account
  recovery). Event-driven off the broker (auth emits events → notification consumes; RabbitMQ,
  or Kafka if migrated).
- Social login — Google / Apple / Facebook (OAuth2 / OIDC) alongside email/password.
- Kafka migration (only if event replay / streaming is needed).
- Per-user features beyond auth (favorites, history, tour playlists).
- Offline tile bundles.

## Open questions

- Audio format/bitrate and caching/TTL strategy for generated content.
- Unlock radius value, and whether unlocking is transient (only while near) or persisted per user
  (once visited, stays unlocked). Where unlock state lives if persisted (auth/user service vs client).
- Content-mode override: whether per-POI overrides are remembered or one-off.
- Retry policy specifics (max attempts, backoff) and DLQ handling / reprocessing.

Resolved (see `flows/`): status delivery = polling for MVP (SSE later); generation
dedup = Redis `SET NX` per (poiId, mode); failures = capped retry + DLQ + `FAILED` status.
Tiles = **tileserver-gl** served from an mbtiles seed **generated by Planetiler** (`basemap`
profile → OpenMapTiles schema) out of the Geofabrik *dolnośląskie* extract, cropped to a
Wrocław bbox. Built via a `make tiles` target into `infra/tiles/wroclaw.mbtiles` (gitignored,
not committed); prerequisite step before `docker compose up`.
