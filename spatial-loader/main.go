package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type FeatureCollection struct {
	Features []Feature `json:"features"`
}

type Feature struct {
	Geometry   Geometry               `json:"geometry"`
	Properties map[string]interface{} `json:"properties"`
}

type Geometry struct {
	Coordinates [2]float64 `json:"coordinates"` // [lon, lat]
}

var dedicatedColumns = map[string]struct{}{
	"@id":          {},
	"name":         {},
	"alt_name":     {},
	"official_name": {},
	"short_name":   {},
	"name:en":      {},
	"name:pl":      {},
	"description":  {},
	"inscription":  {},
	"artist_name":  {},
	"artwork_type": {},
}

const insertSQL = `
INSERT INTO location_point (
    osm_id,
    geom,
    name,
    alt_name,
    official_name,
    short_name,
    "name:en",
    "name:pl",
    description,
    inscription,
    artist_name,
    artwork_type,
    tags
)
VALUES (
    $1,
    ST_SetSRID(ST_MakePoint($2, $3), 4326),
    $4, $5, $6, $7, $8, $9, $10, $11, $12, $13,
    $14
)
ON CONFLICT (osm_id) DO UPDATE SET
    geom          = EXCLUDED.geom,
    name          = EXCLUDED.name,
    alt_name      = EXCLUDED.alt_name,
    official_name = EXCLUDED.official_name,
    short_name    = EXCLUDED.short_name,
    "name:en"     = EXCLUDED."name:en",
    "name:pl"     = EXCLUDED."name:pl",
    description   = EXCLUDED.description,
    inscription   = EXCLUDED.inscription,
    artist_name   = EXCLUDED.artist_name,
    artwork_type  = EXCLUDED.artwork_type,
    tags          = EXCLUDED.tags;
`

func strPtr(m map[string]interface{}, key string) *string {
	v, ok := m[key]
	if !ok || v == nil {
		return nil
	}
	s, ok := v.(string)
	if !ok || s == "" {
		return nil
	}
	return &s
}

func buildArgs(f Feature) ([]interface{}, error) {
	p := f.Properties

	osmID := strPtr(p, "@id")
	if osmID == nil {
		return nil, fmt.Errorf("brak @id")
	}

	// Wszystko poza dedykowanymi kolumnami → tags JSONB
	tags := make(map[string]interface{}, len(p))
	for k, v := range p {
		if _, skip := dedicatedColumns[k]; skip {
			continue
		}
		if v != nil && v != "" {
			tags[k] = v
		}
	}
	tagsJSON, err := json.Marshal(tags)
	if err != nil {
		return nil, fmt.Errorf("marshal tags: %w", err)
	}

	return []interface{}{
		*osmID,
		f.Geometry.Coordinates[0], // lon
		f.Geometry.Coordinates[1], // lat
		strPtr(p, "name"),
		strPtr(p, "alt_name"),
		strPtr(p, "official_name"),
		strPtr(p, "short_name"),
		strPtr(p, "name:en"),
		strPtr(p, "name:pl"),
		strPtr(p, "description"),
		strPtr(p, "inscription"),
		strPtr(p, "artist_name"),
		strPtr(p, "artwork_type"),
		tagsJSON,
	}, nil
}

func main() {
	dsn := flag.String("dsn", "", `DSN połączenia, np. "postgres://user:pass@localhost:5432/mydb"`)
	geojsonPath := flag.String("geojson", "data.geojson", "Ścieżka do pliku GeoJSON")
	batchSize := flag.Int("batch", 500, "Rozmiar batcha")
	flag.Parse()

	if *dsn == "" {
		fmt.Fprintln(os.Stderr, "Wymagany parametr --dsn")
		flag.Usage()
		os.Exit(1)
	}

	ctx := context.Background()

	// --- Wczytaj GeoJSON ---
	log.Printf("Wczytuję %s …", *geojsonPath)
	f, err := os.Open(*geojsonPath)
	if err != nil {
		log.Fatalf("Nie można otworzyć pliku: %v", err)
	}
	defer f.Close()

	var fc FeatureCollection
	if err := json.NewDecoder(f).Decode(&fc); err != nil {
		log.Fatalf("Błąd parsowania GeoJSON: %v", err)
	}
	log.Printf("Wczytano %d featur.", len(fc.Features))

	// --- Połącz z bazą ---
	pool, err := pgxpool.New(ctx, *dsn)
	if err != nil {
		log.Fatalf("Błąd połączenia z bazą: %v", err)
	}
	defer pool.Close()

	if err := pool.Ping(ctx); err != nil {
		log.Fatalf("Baza niedostępna: %v", err)
	}
	log.Println("Połączono z bazą.")

	// --- Buduj batch i ładuj ---
	total := len(fc.Features)
	inserted := 0
	skipped := 0

	batch := &pgx.Batch{}

	flush := func() error {
		if batch.Len() == 0 {
			return nil
		}
		br := pool.SendBatch(ctx, batch)
		defer br.Close()
		for i := 0; i < batch.Len(); i++ {
			if _, err := br.Exec(); err != nil {
				return fmt.Errorf("exec w batchu: %w", err)
			}
		}
		batch = &pgx.Batch{}
		return nil
	}

	for i, feat := range fc.Features {
		args, err := buildArgs(feat)
		if err != nil {
			log.Printf("POMINIĘTO feature %d: %v", i, err)
			skipped++
			continue
		}

		batch.Queue(insertSQL, args...)
		inserted++

		if batch.Len() >= *batchSize {
			if err := flush(); err != nil {
				log.Fatalf("Błąd flushowania batcha: %v", err)
			}
			pct := float64(inserted+skipped) / float64(total) * 100
			log.Printf("  Postęp: %d/%d (%.1f%%)", inserted+skipped, total, pct)
		}
	}

	if err := flush(); err != nil {
		log.Fatalf("Błąd ostatniego flushowania: %v", err)
	}

	log.Printf("Gotowe. Załadowano: %d, pominięto: %d.", inserted, skipped)
}
