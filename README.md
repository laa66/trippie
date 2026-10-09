# trippie

Location-aware app that turns the space around you into a self-guided tour. Discovers points
of interest (artworks, monuments, landmarks) from OpenStreetMap near your position, shows them
on a map, and (once unlocked by approach) generates AI-guided descriptions (text + narrated audio).

**Status: M2 (auth slice) implemented, awaiting its manual E2E gate.** M0 (skeleton) and M1
(POI discovery) are closed. M2 adds accounts, OTP email verification, password recovery,
per-user settings, and turns discovery into an authenticated feature. Content generation
(M4/M5) and proximity unlock (M3) are not started.
See `docs/ARCHITECTURE.md` for the target system, and `docs/flows/` for per-flow sequence diagrams.

## Modules

| Path                      | Stack                                  | Status                       |
|---------------------------|----------------------------------------|------------------------------|
| `submodules/gui`          | Vue 3 + Ionic 8 + Tailwind v4 + MapLibre GL | Map with categorized POI markers; register / verify / login / forgot / reset-password screens, settings, route guard. Functional only — no visual polish yet. |
| `submodules/spatial`      | Java 21 + Spring Boot                  | `GET /locations/nearby` over PostGIS (JdbcClient + native SQL), distance-sorted, category-filtered. |
| `submodules/gateway`      | Spring Cloud Gateway 5.0.0             | Routes `/api/spatial/**` and `/api/auth/**`; reactive OAuth2 resource server validating ES256 locally against auth's JWKS, plus a Redis `jti` denylist. |
| `submodules/commons`      | Java (Gradle composite build)          | Shared backend utilities, consumed at source. |
| `submodules/spatial-loader` | Go                                   | One-shot Overpass → PostGIS ingestion CLI (`make load-pois`). |
| `submodules/auth`         | Java 21 + Spring Boot (virtual threads) | Owns `auth_db` via Flyway. Register, OTP verify/resend, login, refresh, logout, forgot/reset password, per-user settings. ES256 minting, refresh rotation in Redis behind an httpOnly cookie. |
| `submodules/content`      | —                                      | Placeholder. Content generation service scaffolded in M4. |
| `submodules/worker`       | —                                      | Placeholder. Async content-generation worker scaffolded in M4. |
| `infra/`                  | Docker Compose + init SQL              | Postgres (PostGIS), Redis, tileserver-gl and all services start with `make up`; RabbitMQ stays behind `profiles:[infra]` until M4. |

## Running locally

**Prerequisites:** Docker, `make`. The build is driven from the root `Makefile`; each
submodule is an independent build. There is no root `docker-compose.yml` — the stack lives in
`infra/docker-compose.yml` and `make` is the entry point.

```bash
# 1. Generate self-hosted tiles (run once, then reuse the cache)
make tiles
# Generates infra/tiles/wroclaw.mbtiles via Planetiler (basemap → OpenMapTiles schema)
# from the Geofabrik dolnośląskie extract, cropped to Wrocław. Gitignored; cached locally.

# 2. Generate the dev JWT signing key (run once; refuses to overwrite an existing one)
make auth-keys
# Writes a dev-only ES256 private JWK to infra/auth-keys/dev-jwk.json, mounted read-only
# into the auth container. Gitignored, never committed. Prod uses a real secret.

# 3. Bring up the stack (detached)
make up

# 4. Seed POIs into PostGIS (run once per database; needs the stack up)
make load-pois
# Queries public overpass-api.de for the same Wrocław bbox as the tiles and upserts
# the POIs. Idempotent — rerunning only refreshes rows.

# 5. Tear down (-v also drops the named volumes, so POIs and accounts are gone)
make down
```

**Single entry point:** The browser talks to **http://localhost:8080** only — the gui's nginx.
It serves the SPA and proxies:
- `/api` → gateway (which routes to auth and spatial)
- `/health` → gateway (which routes to spatial)
- `/styles`, `/data`, `/fonts` → tileserver-gl (tile assets)

**Data layer (dev credentials):** Postgres and Redis start with `make up` — spatial needs
PostGIS, auth needs both. RabbitMQ sits behind `profiles: [infra]` and stays off until M4:

```bash
docker compose -f infra/docker-compose.yml --profile infra up -d
```

Everything binds to loopback only. DEV-ONLY creds: user `trippie`, pass `trippie` (Postgres,
RabbitMQ). No production secrets here.

## Testing

Three targets from the repo root. The split is by **class-name convention, not by Docker
detection** — `int-test` runs every `*IntegrationTest` / `*IntTest` class plus the
container-backed Go package, which is why it needs a Docker daemon even though a few of those
ITs would run without one (`GatewayRoutingIntegrationTest`,
`GatewayDenylistFailClosedIntegrationTest`, `JwtRoundTripIntegrationTest`).

```bash
make unit-test   # no Docker needed: Java *Test, Go -short, gui vitest
make int-test    # needs a running Docker daemon (Testcontainers: Postgres/PostGIS, Redis)
make test        # both, in order; stops at the first failure
```

Test caching is deliberately bypassed for `int-test` (Gradle `upToDateWhen { false }`, Go
`-count=1`): neither build system tracks the Docker daemon's state, so a cached "success"
would otherwise report ITs as passing without starting a single container. Each test task is
also guarded against matching zero classes — a filter that finds nothing fails the build
instead of reporting green.

Per module, if you want one suite on its own:

```bash
cd submodules/spatial       && ./gradlew unitTest   # or integrationTest, or test for both
cd submodules/gateway       && ./gradlew unitTest
cd submodules/auth          && ./gradlew unitTest
cd submodules/spatial-loader && go test -short ./...        # unit
cd submodules/spatial-loader && go test -count=1 ./internal/loader/...   # IT, needs Docker
cd submodules/gui           && npm ci && npm test
cd submodules/gui           && npm run build   # type-check + production build
```

## Manual verification — M2 auth slice

Automated suites do not cover the browser end of the auth flows, so M2 closes on this
walk-through. It assumes a clean database: `make down && make up && make load-pois`.

**1. Sign up and verify.** Open http://localhost:8080, register an account. The OTP is not
emailed in dev — it is logged by the auth service:

```bash
docker logs trippie-auth 2>&1 | grep 'dev-stub OtpMailer'
# [dev-stub OtpMailer] VERIFY OTP for you@example.com: 123456
```

Enter the code. Logging in before verifying must fail with an "email not verified" message.

**2. Authenticated discovery.** After logging in, the map renders Wrocław POIs. The same
endpoint without a token must be refused:

```bash
curl -i "http://localhost:8080/api/spatial/locations/nearby?lat=51.11&lon=17.03"
# HTTP/1.1 401 Unauthorized
```

**3. Settings.** Change the default content mode and uncheck a category. Markers update
without a reload, and the choice survives a reload (it is stored server-side, per user).

**4. Refresh rotation.** Leave the tab open past the 15-minute access-token TTL, then
interact with the map. The session must continue without a re-login, and the refresh family
in Redis must show a new current token:

```bash
docker exec trippie-redis redis-cli --scan --pattern 'auth:refresh:*'
```

**5. Logout revokes instantly.** Log out, then confirm the access token is denylisted and the
refresh cookie is dead:

```bash
docker exec trippie-redis redis-cli --scan --pattern 'auth:denylist:*'
```

**6. Password recovery.** Use "forgot password", read the `RESET` OTP from the auth log the
same way, set a new password. The old password must stop working and the new one must work.

**7. Negative logout leg (M2-19).** This is the one that needs the stack broken on purpose:

```bash
docker stop trippie-auth
```

Log in first (do this before stopping auth), then click **Wyloguj**. Within 5 seconds the app
must land on `/login` and show the notice that the previous logout was not confirmed by the
server. While that request hangs, **click Wyloguj repeatedly** — only one logout leg may
actually start (unit tests can only pin the `disabled` prop; the real double-submit block is
Ionic's runtime and is observable only here). Then:

```bash
docker start trippie-auth
```

Reload the app. It must **not** be logged in, and the auth log must show the replayed
bearer-less logout. Finally confirm the pre-logout refresh cookie can no longer rotate — copy
the `refresh_token` and `csrf` cookie values from DevTools before logging out, then:

```bash
curl -i -X POST http://localhost:8080/api/auth/refresh \
  -H "X-CSRF-Token: <csrf value>" \
  -b "refresh_token=<refresh value>; csrf=<csrf value>"
# HTTP/1.1 401 Unauthorized
```

## What's next

M3 adds the proximity unlock slice; M4/M5 bring AI-generated text and narrated audio, closing
the MVP loop. `docs/ARCHITECTURE.md` records the MVP scope and the design decisions behind it,
`.claude/PLAN.md` the milestone breakdown.
