// Package loader upserts parsed Overpass elements into location_point. It is the
// only writer to that table: idempotency comes from ON CONFLICT (osm_id) DO
// UPDATE, and updated_at is set to now() on both INSERT and UPDATE so the column
// answers "when did we last see this POI in OSM", not "when did we first see it".
package loader

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/laa66/trippie/spatial-loader/internal/mapping"
	"github.com/laa66/trippie/spatial-loader/internal/overpass"
)

const upsertSQL = `
INSERT INTO location_point (osm_id, name, description, category, geom, tags, updated_at)
VALUES ($1, $2, $3, $4, ST_SetSRID(ST_MakePoint($5, $6), 4326)::geography, $7::jsonb, now())
ON CONFLICT (osm_id) DO UPDATE SET
    name        = EXCLUDED.name,
    description = EXCLUDED.description,
    category    = EXCLUDED.category,
    geom        = EXCLUDED.geom,
    tags        = EXCLUDED.tags,
    updated_at  = now()`

// Summary reports the outcome of a run.
type Summary struct {
	Upserted int
	Skipped  int
}

// Upsert maps each element to a row and upserts it. Elements that cannot become
// a row (no category match, no resolvable point) are skipped and counted. All
// rows are sent as one pgx batch, which pgx runs in an implicit transaction, so
// every row in a run shares the same now().
func Upsert(ctx context.Context, pool *pgxpool.Pool, elements []overpass.Element) (Summary, error) {
	var s Summary
	batch := &pgx.Batch{}
	for _, e := range elements {
		row, ok := mapping.BuildRow(e)
		if !ok {
			s.Skipped++
			continue
		}
		tagsJSON, err := json.Marshal(row.Tags)
		if err != nil {
			return s, fmt.Errorf("marshal tags for %s: %w", row.OSMID, err)
		}
		batch.Queue(upsertSQL, row.OSMID, row.Name, row.Description,
			string(row.Category), row.Lon, row.Lat, string(tagsJSON))
		s.Upserted++
	}

	if batch.Len() == 0 {
		return s, nil
	}

	br := pool.SendBatch(ctx, batch)
	defer br.Close()
	for range batch.Len() {
		if _, err := br.Exec(); err != nil {
			return s, fmt.Errorf("upsert batch exec: %w", err)
		}
	}
	return s, nil
}
