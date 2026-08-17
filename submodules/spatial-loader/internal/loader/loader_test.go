package loader

import (
	"context"
	"log"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/laa66/trippie/spatial-loader/internal/overpass"
)

// migrationPath points at the REAL Flyway migration spatial owns, resolved
// relative to this package. The test applies that exact file, not a copy.
const migrationPath = "../../../spatial/src/main/resources/db/migration/V1__location_point.sql"

// testPool is the singleton connection to a postgis container started once for
// the whole package (the Go analog of the singleton-container convention).
var testPool *pgxpool.Pool

func TestMain(m *testing.M) {
	ctx := context.Background()

	ctr, err := postgres.Run(ctx, "postgis/postgis:18-3.6-alpine",
		postgres.WithDatabase("spatial_db"),
		postgres.WithUsername("test"),
		postgres.WithPassword("test"),
		postgres.BasicWaitStrategies(),
	)
	if err != nil {
		log.Fatalf("start postgis container: %v", err)
	}

	dsn, err := ctr.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		log.Fatalf("connection string: %v", err)
	}
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		log.Fatalf("connect pool: %v", err)
	}
	if err := applyMigration(ctx, pool); err != nil {
		log.Fatalf("apply migration: %v", err)
	}
	testPool = pool

	code := m.Run()

	pool.Close()
	if err := testcontainers.TerminateContainer(ctr); err != nil {
		log.Printf("terminate container: %v", err)
	}
	os.Exit(code)
}

func applyMigration(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, "CREATE EXTENSION IF NOT EXISTS postgis"); err != nil {
		return err
	}
	sql, err := os.ReadFile(migrationPath)
	if err != nil {
		return err
	}
	_, err = pool.Exec(ctx, string(sql))
	return err
}

func loadFixture(t *testing.T) []overpass.Element {
	t.Helper()
	payload, err := os.ReadFile("testdata/fixture.json")
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	resp, err := overpass.Parse(payload)
	if err != nil {
		t.Fatalf("parse fixture: %v", err)
	}
	return resp.Elements
}

func reset(t *testing.T, ctx context.Context) {
	t.Helper()
	if _, err := testPool.Exec(ctx, "TRUNCATE location_point"); err != nil {
		t.Fatalf("truncate: %v", err)
	}
}

func TestUpsertMapsCategoriesAndSkips(t *testing.T) {
	ctx := context.Background()
	reset(t, ctx)
	elements := loadFixture(t)

	s, err := Upsert(ctx, testPool, elements)
	if err != nil {
		t.Fatalf("Upsert: %v", err)
	}
	// 10 matched with a resolvable point; 3 skipped: 2 unmatched + 1 node with
	// no coordinates.
	if s.Upserted != 10 || s.Skipped != 3 {
		t.Fatalf("summary = %+v; want {Upserted:10 Skipped:3}", s)
	}

	// All 8 category slugs resolved.
	var distinct int
	if err := testPool.QueryRow(ctx, "SELECT count(DISTINCT category) FROM location_point").Scan(&distinct); err != nil {
		t.Fatalf("count categories: %v", err)
	}
	if distinct != 8 {
		t.Fatalf("distinct categories = %d; want 8", distinct)
	}

	var total int
	if err := testPool.QueryRow(ctx, "SELECT count(*) FROM location_point").Scan(&total); err != nil {
		t.Fatalf("count rows: %v", err)
	}
	if total != 10 {
		t.Fatalf("row count = %d; want 10", total)
	}
}

func TestUpsertMultiMatchPicksMostSpecific(t *testing.T) {
	ctx := context.Background()
	reset(t, ctx)

	if _, err := Upsert(ctx, testPool, loadFixture(t)); err != nil {
		t.Fatalf("Upsert: %v", err)
	}
	// node/2 is historic=monument + tourism=attraction -> monuments wins.
	var category string
	if err := testPool.QueryRow(ctx, "SELECT category FROM location_point WHERE osm_id = 'node/2'").Scan(&category); err != nil {
		t.Fatalf("query node/2: %v", err)
	}
	if category != "monuments" {
		t.Fatalf("multi-match category = %q; want monuments", category)
	}
}

func TestUpsertColumnAndJSONBSplit(t *testing.T) {
	ctx := context.Background()
	reset(t, ctx)

	if _, err := Upsert(ctx, testPool, loadFixture(t)); err != nil {
		t.Fatalf("Upsert: %v", err)
	}

	var name, description, category string
	var hasName, hasDescription bool
	var artist string
	err := testPool.QueryRow(ctx, `
		SELECT name, description, category,
		       tags ? 'name', tags ? 'description',
		       tags->>'artist_name'
		FROM location_point WHERE osm_id = 'node/1'`).
		Scan(&name, &description, &category, &hasName, &hasDescription, &artist)
	if err != nil {
		t.Fatalf("query node/1: %v", err)
	}
	if name != "Papa Krasnal" || description != "A dwarf statue" || category != "public_art" {
		t.Fatalf("dedicated columns wrong: name=%q description=%q category=%q", name, description, category)
	}
	if hasName || hasDescription {
		t.Fatal("name/description must not be duplicated into tags JSONB")
	}
	if artist != "Tomasz Moczek" {
		t.Fatalf("non-dedicated tag missing from JSONB: artist_name=%q", artist)
	}
}

func TestUpsertPointsLandAsGeography(t *testing.T) {
	ctx := context.Background()
	reset(t, ctx)

	if _, err := Upsert(ctx, testPool, loadFixture(t)); err != nil {
		t.Fatalf("Upsert: %v", err)
	}

	// geom is geography; casting to geometry lets us read back X (lon) / Y (lat).
	assertPoint := func(osmID string, wantLon, wantLat float64) {
		t.Helper()
		var lon, lat, srid float64
		err := testPool.QueryRow(ctx, `
			SELECT ST_X(geom::geometry), ST_Y(geom::geometry), ST_SRID(geom::geometry)
			FROM location_point WHERE osm_id = $1`, osmID).Scan(&lon, &lat, &srid)
		if err != nil {
			t.Fatalf("query geom %s: %v", osmID, err)
		}
		if srid != 4326 {
			t.Fatalf("%s srid = %v; want 4326", osmID, srid)
		}
		if !approx(lon, wantLon) || !approx(lat, wantLat) {
			t.Fatalf("%s point = (%v,%v); want (%v,%v)", osmID, lon, lat, wantLon, wantLat)
		}
	}
	assertPoint("way/3", 17.045, 51.101)  // way uses its center
	assertPoint("node/1", 17.031, 51.110) // node uses its lat/lon
	assertPoint("relation/11", 17.050, 51.103)
}

func TestUpsertIsIdempotentAndMovesUpdatedAt(t *testing.T) {
	ctx := context.Background()
	reset(t, ctx)
	elements := loadFixture(t)

	if _, err := Upsert(ctx, testPool, elements); err != nil {
		t.Fatalf("first Upsert: %v", err)
	}
	var firstCount int
	var firstUpdated time.Time
	if err := testPool.QueryRow(ctx, "SELECT count(*) FROM location_point").Scan(&firstCount); err != nil {
		t.Fatalf("count: %v", err)
	}
	if err := testPool.QueryRow(ctx, "SELECT updated_at FROM location_point WHERE osm_id = 'node/1'").Scan(&firstUpdated); err != nil {
		t.Fatalf("updated_at: %v", err)
	}

	time.Sleep(20 * time.Millisecond)

	s, err := Upsert(ctx, testPool, elements)
	if err != nil {
		t.Fatalf("second Upsert: %v", err)
	}
	if s.Upserted != 10 || s.Skipped != 3 {
		t.Fatalf("second run summary = %+v; want {10 3}", s)
	}

	var secondCount int
	var secondUpdated time.Time
	if err := testPool.QueryRow(ctx, "SELECT count(*) FROM location_point").Scan(&secondCount); err != nil {
		t.Fatalf("count: %v", err)
	}
	if err := testPool.QueryRow(ctx, "SELECT updated_at FROM location_point WHERE osm_id = 'node/1'").Scan(&secondUpdated); err != nil {
		t.Fatalf("updated_at: %v", err)
	}

	if secondCount != firstCount {
		t.Fatalf("row count changed on re-run: %d -> %d", firstCount, secondCount)
	}
	if !secondUpdated.After(firstUpdated) {
		t.Fatalf("updated_at did not move forward on re-run: %v -> %v", firstUpdated, secondUpdated)
	}
}

func approx(a, b float64) bool {
	const eps = 1e-6
	d := a - b
	return d < eps && d > -eps
}
