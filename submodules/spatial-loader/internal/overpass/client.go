// Package overpass is a polite HTTP client for the public Overpass API.
//
// It fetches raw Overpass JSON for a bounding box with one query per run, an
// explicit User-Agent, exponential backoff on 429/504 that honors Retry-After,
// and an on-disk payload cache so dev re-runs can replay without touching the
// donated public service. Element->row mapping is out of scope (M1-03).
package overpass

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// DefaultEndpoint is the public community Overpass instance.
const DefaultEndpoint = "https://overpass-api.de/api/interpreter"

const (
	defaultUserAgent    = "trippie-spatial-loader/0.1 (+https://github.com/laa66/trippie)"
	defaultMaxRetries   = 4
	defaultBaseDelay    = time.Second
	defaultMaxBackoff   = 60 * time.Second
	defaultQueryTimeout = 180
)

// BBox is a geographic rectangle in Planetiler axis order (the same order the
// Makefile single-sources for map tiles). Overpass wants a different order;
// overpassOrder handles the reorder so callers never have to.
type BBox struct {
	West, South, East, North float64
}

// overpassOrder renders the box as Overpass expects it: south,west,north,east.
// Planetiler (and this struct) uses west,south,east,north — same four numbers,
// different order, a classic silent bug this method centralizes.
func (b BBox) overpassOrder() string {
	return fmt.Sprintf("%s,%s,%s,%s",
		formatCoord(b.South), formatCoord(b.West),
		formatCoord(b.North), formatCoord(b.East))
}

func formatCoord(f float64) string {
	return strconv.FormatFloat(f, 'f', -1, 64)
}

// Query builds the Overpass QL for the box. nwr + `out center;` makes ways and
// relations yield a single representative point alongside nodes.
func (b BBox) Query(serverTimeout int) string {
	return fmt.Sprintf("[out:json][timeout:%d];\nnwr(%s);\nout center;",
		serverTimeout, b.overpassOrder())
}

// Config configures a Client. Zero-value fields fall back to sensible defaults.
type Config struct {
	Endpoint     string
	UserAgent    string
	HTTPClient   *http.Client
	MaxRetries   int
	BaseDelay    time.Duration
	MaxBackoff   time.Duration // ceiling for a single backoff wait
	QueryTimeout int           // Overpass server-side timeout, seconds

	// sleep is an injection seam for tests; nil means real timer-based waiting.
	// It must honor ctx (return ctx.Err() on cancellation) to stay faithful to
	// the production path.
	sleep func(context.Context, time.Duration) error
}

// Client talks to a single Overpass endpoint.
type Client struct {
	endpoint     string
	userAgent    string
	httpClient   *http.Client
	maxRetries   int
	baseDelay    time.Duration
	maxBackoff   time.Duration
	queryTimeout int
	sleep        func(context.Context, time.Duration) error
}

// NewClient builds a Client, applying defaults for any unset Config field.
func NewClient(cfg Config) *Client {
	c := &Client{
		endpoint:     cfg.Endpoint,
		userAgent:    cfg.UserAgent,
		httpClient:   cfg.HTTPClient,
		maxRetries:   cfg.MaxRetries,
		baseDelay:    cfg.BaseDelay,
		maxBackoff:   cfg.MaxBackoff,
		queryTimeout: cfg.QueryTimeout,
		sleep:        cfg.sleep,
	}
	if c.endpoint == "" {
		c.endpoint = DefaultEndpoint
	}
	if c.userAgent == "" {
		c.userAgent = defaultUserAgent
	}
	if c.httpClient == nil {
		c.httpClient = &http.Client{Timeout: 300 * time.Second}
	}
	if c.maxRetries <= 0 {
		c.maxRetries = defaultMaxRetries
	}
	if c.baseDelay <= 0 {
		c.baseDelay = defaultBaseDelay
	}
	if c.maxBackoff <= 0 {
		c.maxBackoff = defaultMaxBackoff
	}
	if c.queryTimeout <= 0 {
		c.queryTimeout = defaultQueryTimeout
	}
	return c
}

// Load resolves the raw payload for the box. If inputPath is set it replays that
// saved file and makes zero network calls; otherwise it fetches once and, when
// cachePath is set, writes the raw upstream payload to disk.
func (c *Client) Load(ctx context.Context, b BBox, inputPath, cachePath string) ([]byte, error) {
	if inputPath != "" {
		return os.ReadFile(inputPath)
	}
	payload, err := c.Fetch(ctx, b)
	if err != nil {
		return nil, err
	}
	if cachePath != "" {
		if err := os.WriteFile(cachePath, payload, 0o644); err != nil {
			return nil, fmt.Errorf("write cache file %q: %w", cachePath, err)
		}
	}
	return payload, nil
}

// Fetch runs exactly one Overpass query for the box and returns the raw JSON
// body. On 429/504 it retries with exponential backoff (honoring Retry-After
// when present) up to a capped attempt count, then gives up with an error.
func (c *Client) Fetch(ctx context.Context, b BBox) ([]byte, error) {
	query := b.Query(c.queryTimeout)
	var lastErr error
	for attempt := 0; ; attempt++ {
		body, retryAfter, status, err := c.do(ctx, query)
		if err == nil {
			return body, nil
		}
		lastErr = err
		if !retryableStatus(status) {
			return nil, err
		}
		if attempt >= c.maxRetries {
			break
		}
		delay := jitter(cappedExpBackoff(c.baseDelay, attempt, c.maxBackoff))
		if retryAfter > 0 {
			delay = retryAfter // honor the server's explicit instruction verbatim
		}
		if err := c.wait(ctx, delay); err != nil {
			return nil, err
		}
	}
	return nil, fmt.Errorf("overpass: giving up after %d attempts: %w", c.maxRetries+1, lastErr)
}

func retryableStatus(status int) bool {
	return status == http.StatusTooManyRequests || status == http.StatusGatewayTimeout
}

// cappedExpBackoff returns base*2^attempt clamped to [base, max]. Large attempts
// would overflow the int64 shift into a negative Duration (immediate busy-retry);
// clamping keeps every wait bounded and polite toward the donated public service.
func cappedExpBackoff(base time.Duration, attempt int, max time.Duration) time.Duration {
	if attempt < 0 {
		attempt = 0
	}
	// A shift of >=63 overflows; anything that large is already past the cap.
	if attempt >= 63 {
		return max
	}
	delay := base << attempt
	if delay <= 0 || delay > max {
		return max
	}
	return delay
}

// jitter spreads a delay over [d/2, d] (equal jitter) so retrying clients do not
// synchronize and hammer the endpoint in lockstep.
func jitter(d time.Duration) time.Duration {
	if d <= 0 {
		return 0
	}
	half := d / 2
	return half + time.Duration(rand.Int64N(int64(half)+1))
}

// do performs a single HTTP attempt. On a non-2xx it returns the parsed
// Retry-After delay (zero if absent/invalid), the status code, and an error.
func (c *Client) do(ctx context.Context, query string) (body []byte, retryAfter time.Duration, status int, err error) {
	form := url.Values{"data": {query}}.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, strings.NewReader(form))
	if err != nil {
		return nil, 0, 0, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("User-Agent", c.userAgent)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, 0, 0, err
	}
	defer resp.Body.Close()

	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, 0, resp.StatusCode, fmt.Errorf("read body: %w", err)
	}
	if resp.StatusCode == http.StatusOK {
		return payload, 0, resp.StatusCode, nil
	}
	retryAfter = parseRetryAfter(resp.Header.Get("Retry-After"), time.Now())
	return nil, retryAfter, resp.StatusCode, fmt.Errorf("overpass: unexpected status %d", resp.StatusCode)
}

func (c *Client) wait(ctx context.Context, d time.Duration) error {
	if c.sleep != nil {
		return c.sleep(ctx, d)
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// parseRetryAfter reads an HTTP Retry-After value in either supported form:
// delta-seconds or an HTTP-date. Absent/invalid/past values yield zero.
func parseRetryAfter(h string, now time.Time) time.Duration {
	h = strings.TrimSpace(h)
	if h == "" {
		return 0
	}
	if secs, err := strconv.Atoi(h); err == nil {
		if secs <= 0 {
			return 0
		}
		return time.Duration(secs) * time.Second
	}
	if t, err := http.ParseTime(h); err == nil {
		if d := t.Sub(now); d > 0 {
			return d
		}
	}
	return 0
}

// Response is the minimal shape of an Overpass JSON payload — enough to prove a
// successful fetch. Full element->row decoding lives in M1-03.
type Response struct {
	Elements []Element `json:"elements"`
}

// Element is a single Overpass result. With `out center` ways/relations carry a
// Center; nodes carry Lat/Lon directly.
type Element struct {
	Type   string            `json:"type"`
	ID     int64             `json:"id"`
	Lat    float64           `json:"lat"`
	Lon    float64           `json:"lon"`
	Center *Center           `json:"center"`
	Tags   map[string]string `json:"tags"`
}

// Center is the representative point of a way/relation under `out center`.
type Center struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

// Parse decodes a raw payload into the minimal typed model.
func Parse(payload []byte) (*Response, error) {
	var r Response
	if err := json.Unmarshal(payload, &r); err != nil {
		return nil, fmt.Errorf("decode overpass json: %w", err)
	}
	return &r, nil
}
