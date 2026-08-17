package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/laa66/trippie/spatial-loader/internal/loader"
	"github.com/laa66/trippie/spatial-loader/internal/overpass"
)

func main() {
	endpoint := flag.String("endpoint", overpass.DefaultEndpoint, "Overpass API endpoint")
	bboxStr := flag.String("bbox", "", "bounding box as west,south,east,north (Planetiler order); required unless --input is set")
	userAgent := flag.String("user-agent", "", "User-Agent header (defaults to the loader's identity)")
	cacheFile := flag.String("cache-file", "", "write the raw upstream payload to this path")
	inputFile := flag.String("input", "", "replay a saved payload from this path, making zero network calls")
	dsn := flag.String("dsn", "", "PostgreSQL DSN; when set, upsert the POIs into location_point (defaults to $DATABASE_URL)")
	maxRetries := flag.Int("max-retries", 0, "max retries on 429/504 (0 uses the built-in default)")
	baseDelay := flag.Duration("base-delay", 0, "base backoff delay (0 uses the built-in default)")
	queryTimeout := flag.Int("query-timeout", 0, "Overpass server-side [timeout:N] seconds (0 uses the built-in default)")
	flag.Parse()

	resolvedDSN := *dsn
	if resolvedDSN == "" {
		resolvedDSN = os.Getenv("DATABASE_URL")
	}

	if err := run(*endpoint, *bboxStr, *userAgent, *cacheFile, *inputFile, resolvedDSN, *maxRetries, *baseDelay, *queryTimeout); err != nil {
		log.Fatalf("spatial-loader: %v", err)
	}
}

func run(endpoint, bboxStr, userAgent, cacheFile, inputFile, dsn string, maxRetries int, baseDelay time.Duration, queryTimeout int) error {
	ctx := context.Background()

	var bbox overpass.BBox
	if inputFile == "" {
		var err error
		bbox, err = parseBBox(bboxStr)
		if err != nil {
			return err
		}
	}

	client := overpass.NewClient(overpass.Config{
		Endpoint:     endpoint,
		UserAgent:    userAgent,
		MaxRetries:   maxRetries,
		BaseDelay:    baseDelay,
		QueryTimeout: queryTimeout,
	})

	payload, err := client.Load(ctx, bbox, inputFile, cacheFile)
	if err != nil {
		return err
	}

	resp, err := overpass.Parse(payload)
	if err != nil {
		return err
	}

	source := "overpass"
	if inputFile != "" {
		source = inputFile
	}
	log.Printf("fetched %d elements from %s", len(resp.Elements), source)
	if cacheFile != "" && inputFile == "" {
		log.Printf("raw payload cached to %s", cacheFile)
	}

	if dsn == "" {
		log.Print("no DSN provided; skipping database upsert")
		return nil
	}

	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return fmt.Errorf("connect database: %w", err)
	}
	defer pool.Close()

	summary, err := loader.Upsert(ctx, pool, resp.Elements)
	if err != nil {
		return err
	}
	log.Printf("upserted %d, skipped %d", summary.Upserted, summary.Skipped)
	return nil
}

// parseBBox reads "west,south,east,north" (Planetiler order). The reorder to
// Overpass's south,west,north,east is the client's job, not the CLI's.
func parseBBox(s string) (overpass.BBox, error) {
	if s == "" {
		return overpass.BBox{}, fmt.Errorf("--bbox is required (west,south,east,north)")
	}
	parts := strings.Split(s, ",")
	if len(parts) != 4 {
		return overpass.BBox{}, fmt.Errorf("--bbox must have 4 comma-separated numbers, got %d", len(parts))
	}
	nums := make([]float64, 4)
	for i, p := range parts {
		f, err := strconv.ParseFloat(strings.TrimSpace(p), 64)
		if err != nil {
			return overpass.BBox{}, fmt.Errorf("--bbox value %q is not a number: %w", p, err)
		}
		nums[i] = f
	}
	return overpass.BBox{West: nums[0], South: nums[1], East: nums[2], North: nums[3]}, nil
}
