package overpass

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// wroclaw is the Wrocław box in Planetiler order (west,south,east,north).
var wroclaw = BBox{West: 16.80, South: 50.94, East: 17.18, North: 51.21}

const samplePayload = `{"elements":[{"type":"node","id":1,"lat":51.1,"lon":17.0,"tags":{"name":"Rynek"}},{"type":"way","id":2,"center":{"lat":51.11,"lon":17.03},"tags":{"tourism":"museum"}}]}`

// recordingSleeper captures backoff durations instead of really sleeping. It
// still honors ctx so it stays faithful to the production wait path.
type recordingSleeper struct{ delays []time.Duration }

func (r *recordingSleeper) sleep(ctx context.Context, d time.Duration) error {
	r.delays = append(r.delays, d)
	return ctx.Err()
}

func TestBBoxQueryReordersToSouthWestNorthEast(t *testing.T) {
	q := wroclaw.Query(180)
	if !strings.Contains(q, "nwr(50.94,16.8,51.21,17.18)") {
		t.Fatalf("bbox not reordered to south,west,north,east: %q", q)
	}
	if !strings.Contains(q, "out center;") || !strings.Contains(q, "[out:json]") {
		t.Fatalf("query missing out:json / out center: %q", q)
	}
}

func TestFetchOneQueryWithUserAgent(t *testing.T) {
	var hits int32
	var gotUA, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		gotUA = r.Header.Get("User-Agent")
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.Write([]byte(samplePayload))
	}))
	defer srv.Close()

	c := NewClient(Config{Endpoint: srv.URL, UserAgent: "test-agent/1.0"})
	payload, err := c.Fetch(context.Background(), wroclaw)
	if err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Fatalf("expected exactly one query, got %d", got)
	}
	if gotUA != "test-agent/1.0" {
		t.Fatalf("User-Agent not set correctly: %q", gotUA)
	}
	if !strings.Contains(gotBody, "50.94%2C16.8%2C51.21%2C17.18") {
		t.Fatalf("request body missing reordered bbox query: %q", gotBody)
	}
	resp, err := Parse(payload)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if len(resp.Elements) != 2 {
		t.Fatalf("expected 2 elements, got %d", len(resp.Elements))
	}
	if resp.Elements[1].Center == nil || resp.Elements[1].Center.Lat != 51.11 {
		t.Fatalf("way center not parsed: %+v", resp.Elements[1])
	}
}

func TestFetchDefaultUserAgentAlwaysSet(t *testing.T) {
	var gotUA string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUA = r.Header.Get("User-Agent")
		w.Write([]byte(samplePayload))
	}))
	defer srv.Close()

	c := NewClient(Config{Endpoint: srv.URL}) // no UserAgent
	if _, err := c.Fetch(context.Background(), wroclaw); err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if gotUA == "" || gotUA == "Go-http-client/1.1" {
		t.Fatalf("expected explicit default User-Agent, got %q", gotUA)
	}
}

func TestFetchRetries429HonoringRetryAfter(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&hits, 1) == 1 {
			w.Header().Set("Retry-After", "7")
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		w.Write([]byte(samplePayload))
	}))
	defer srv.Close()

	rec := &recordingSleeper{}
	c := NewClient(Config{Endpoint: srv.URL, MaxRetries: 3, BaseDelay: time.Second, sleep: rec.sleep})
	if _, err := c.Fetch(context.Background(), wroclaw); err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if got := atomic.LoadInt32(&hits); got != 2 {
		t.Fatalf("expected 2 attempts (429 then 200), got %d", got)
	}
	if len(rec.delays) != 1 || rec.delays[0] != 7*time.Second {
		t.Fatalf("expected single backoff honoring Retry-After=7s, got %v", rec.delays)
	}
}

func TestFetchRetries504ExponentialBackoff(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if atomic.AddInt32(&hits, 1) <= 2 {
			w.WriteHeader(http.StatusGatewayTimeout) // no Retry-After
			return
		}
		w.Write([]byte(samplePayload))
	}))
	defer srv.Close()

	rec := &recordingSleeper{}
	base := 500 * time.Millisecond
	c := NewClient(Config{Endpoint: srv.URL, MaxRetries: 4, BaseDelay: base, sleep: rec.sleep})
	if _, err := c.Fetch(context.Background(), wroclaw); err != nil {
		t.Fatalf("Fetch: %v", err)
	}
	if len(rec.delays) != 2 {
		t.Fatalf("expected 2 backoffs, got %v", rec.delays)
	}
	// Equal jitter puts attempt i in [ (base*2^i)/2, base*2^i ].
	for i, d := range rec.delays {
		ceiling := base << uint(i)
		if d < ceiling/2 || d > ceiling {
			t.Fatalf("backoff %d = %v, want within [%v, %v] (all: %v)", i, d, ceiling/2, ceiling, rec.delays)
		}
	}
}

func TestFetchGivesUpAfterCappedRetries(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()

	rec := &recordingSleeper{}
	c := NewClient(Config{Endpoint: srv.URL, MaxRetries: 3, BaseDelay: time.Millisecond, sleep: rec.sleep})
	_, err := c.Fetch(context.Background(), wroclaw)
	if err == nil {
		t.Fatal("expected error after exhausting retries")
	}
	if got := atomic.LoadInt32(&hits); got != 4 { // maxRetries + 1
		t.Fatalf("expected 4 attempts (1 + 3 retries), got %d", got)
	}
	if len(rec.delays) != 3 {
		t.Fatalf("expected 3 backoffs, got %v", rec.delays)
	}
}

// TestFetchBackoffRespectsContextCancellation exercises the REAL timer path (no
// injected sleep): a cancel fired during backoff must abort the wait promptly
// instead of blocking for the full delay.
func TestFetchBackoffRespectsContextCancellation(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusGatewayTimeout) // always retryable, forces backoff
	}))
	defer srv.Close()

	// BaseDelay 10s: equal jitter still guarantees a real wait of >=5s, so a
	// cancel-respecting Fetch (returning in tens of ms) is unmistakable from one
	// that blocks on the timer. Threshold 2s sits well below that 5s floor.
	c := NewClient(Config{Endpoint: srv.URL, MaxRetries: 5, BaseDelay: 10 * time.Second})
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(50 * time.Millisecond)
		cancel()
	}()

	start := time.Now()
	_, err := c.Fetch(ctx, wroclaw)
	elapsed := time.Since(start)

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
	if elapsed >= 2*time.Second {
		t.Fatalf("Fetch blocked the backoff timer (%v) instead of returning on cancel", elapsed)
	}
}

func TestCappedExpBackoffNeverNegativeOrZero(t *testing.T) {
	base := time.Second
	max := 60 * time.Second
	for _, attempt := range []int{0, 1, 5, 30, 62, 63, 100, 1000} {
		d := cappedExpBackoff(base, attempt, max)
		if d <= 0 {
			t.Fatalf("attempt %d produced non-positive delay %v (overflow)", attempt, d)
		}
		if d > max {
			t.Fatalf("attempt %d produced %v, exceeds cap %v", attempt, d, max)
		}
	}
	if got := cappedExpBackoff(base, 0, max); got != base {
		t.Fatalf("attempt 0 = %v, want %v", got, base)
	}
	if got := cappedExpBackoff(base, 6, max); got != max { // base<<6 = 64s > 60s cap
		t.Fatalf("attempt 6 = %v, want cap %v", got, max)
	}
}

func TestJitterStaysWithinEqualJitterBounds(t *testing.T) {
	d := time.Second
	for range 1000 {
		j := jitter(d)
		if j < d/2 || j > d {
			t.Fatalf("jitter(%v) = %v, outside [%v, %v]", d, j, d/2, d)
		}
	}
	if jitter(0) != 0 {
		t.Fatal("jitter(0) must be 0")
	}
}

func TestFetchDoesNotRetryNonRetryableStatus(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(http.StatusBadRequest)
	}))
	defer srv.Close()

	c := NewClient(Config{Endpoint: srv.URL, MaxRetries: 3, BaseDelay: time.Millisecond})
	if _, err := c.Fetch(context.Background(), wroclaw); err == nil {
		t.Fatal("expected error on 400")
	}
	if got := atomic.LoadInt32(&hits); got != 1 {
		t.Fatalf("400 must not be retried, got %d attempts", got)
	}
}

func TestLoadCacheFileWritesRawPayload(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(samplePayload))
	}))
	defer srv.Close()

	cachePath := filepath.Join(t.TempDir(), "raw.json")
	c := NewClient(Config{Endpoint: srv.URL})
	payload, err := c.Load(context.Background(), wroclaw, "", cachePath)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	onDisk, err := os.ReadFile(cachePath)
	if err != nil {
		t.Fatalf("read cache: %v", err)
	}
	if string(onDisk) != samplePayload {
		t.Fatalf("cache file does not contain raw payload: %q", onDisk)
	}
	if string(payload) != samplePayload {
		t.Fatalf("returned payload mismatch: %q", payload)
	}
}

func TestLoadInputReplaysWithZeroNetwork(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("network must not be hit during --input replay")
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	inputPath := filepath.Join(t.TempDir(), "saved.json")
	if err := os.WriteFile(inputPath, []byte(samplePayload), 0o644); err != nil {
		t.Fatalf("seed input: %v", err)
	}
	c := NewClient(Config{Endpoint: srv.URL})
	payload, err := c.Load(context.Background(), wroclaw, inputPath, "")
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if string(payload) != samplePayload {
		t.Fatalf("replayed payload mismatch: %q", payload)
	}
}

func TestParseRetryAfter(t *testing.T) {
	now := time.Date(2026, 8, 17, 12, 0, 0, 0, time.UTC)
	cases := []struct {
		in   string
		want time.Duration
	}{
		{"", 0},
		{"5", 5 * time.Second},
		{"  10 ", 10 * time.Second},
		{"0", 0},
		{"-3", 0},
		{"garbage", 0},
		{now.Add(30 * time.Second).UTC().Format(http.TimeFormat), 30 * time.Second},
		{now.Add(-30 * time.Second).UTC().Format(http.TimeFormat), 0},
	}
	for _, tc := range cases {
		if got := parseRetryAfter(tc.in, now); got != tc.want {
			t.Errorf("parseRetryAfter(%q) = %v, want %v", tc.in, got, tc.want)
		}
	}
}
