package mapping

import (
	"testing"

	"github.com/laa66/trippie/spatial-loader/internal/overpass"
)

func TestResolveCategoryAllEight(t *testing.T) {
	cases := []struct {
		name string
		tags map[string]string
		want Category
	}{
		{"public_art", map[string]string{"tourism": "artwork"}, PublicArt},
		{"monument", map[string]string{"historic": "monument"}, Monuments},
		{"memorial", map[string]string{"historic": "memorial"}, Monuments},
		{"heritage via historic", map[string]string{"historic": "castle"}, Heritage},
		{"heritage via heritage tag", map[string]string{"heritage": "2"}, Heritage},
		{"sacred via amenity", map[string]string{"amenity": "place_of_worship"}, Sacred},
		{"sacred via building", map[string]string{"building": "chapel"}, Sacred},
		{"museum", map[string]string{"tourism": "museum"}, Museums},
		{"viewpoint", map[string]string{"tourism": "viewpoint"}, Viewpoints},
		{"architecture via architect", map[string]string{"building": "yes", "architect": "Max Berg"}, Architecture},
		{"architecture via style", map[string]string{"building:architecture": "gothic"}, Architecture},
		{"attraction", map[string]string{"tourism": "attraction"}, Attractions},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := ResolveCategory(tc.tags)
			if !ok || got != tc.want {
				t.Fatalf("ResolveCategory(%v) = %q,%v; want %q", tc.tags, got, ok, tc.want)
			}
		})
	}
}

func TestResolveCategoryFirstMatchWins(t *testing.T) {
	// historic=monument (monuments, prio 2) + tourism=attraction (attractions,
	// prio 8): the most specific — monuments — must win.
	got, ok := ResolveCategory(map[string]string{"historic": "monument", "tourism": "attraction"})
	if !ok || got != Monuments {
		t.Fatalf("multi-match = %q,%v; want %q", got, ok, Monuments)
	}

	// tourism=museum (museums, prio 5) + historic=castle (heritage, prio 3):
	// heritage outranks museums.
	got, ok = ResolveCategory(map[string]string{"tourism": "museum", "historic": "castle"})
	if !ok || got != Heritage {
		t.Fatalf("multi-match = %q,%v; want %q", got, ok, Heritage)
	}
}

func TestResolveCategoryUnmatched(t *testing.T) {
	for _, tags := range []map[string]string{
		nil,
		{},
		{"highway": "bus_stop"},
		{"amenity": "bench"},
		{"building": "yes"}, // plain building with no notability signal
	} {
		if got, ok := ResolveCategory(tags); ok {
			t.Fatalf("ResolveCategory(%v) = %q; want no match", tags, got)
		}
	}
}

func TestBuildRowSplitAndOSMID(t *testing.T) {
	e := overpass.Element{
		Type: "node",
		ID:   42,
		Lat:  51.11,
		Lon:  17.03,
		Tags: map[string]string{
			"tourism":     "artwork",
			"name":        "Krasnal",
			"description": "A dwarf statue",
			"artist_name": "Tomasz Moczek",
			"empty":       "",
		},
	}
	row, ok := BuildRow(e)
	if !ok {
		t.Fatal("BuildRow returned skip for a matching element")
	}
	if row.OSMID != "node/42" {
		t.Fatalf("osm_id = %q; want node/42", row.OSMID)
	}
	if row.Category != PublicArt {
		t.Fatalf("category = %q; want public_art", row.Category)
	}
	if row.Name == nil || *row.Name != "Krasnal" {
		t.Fatalf("name = %v; want Krasnal", row.Name)
	}
	if row.Description == nil || *row.Description != "A dwarf statue" {
		t.Fatalf("description = %v; want A dwarf statue", row.Description)
	}
	if _, has := row.Tags["name"]; has {
		t.Error("name must not appear in tags JSONB")
	}
	if _, has := row.Tags["description"]; has {
		t.Error("description must not appear in tags JSONB")
	}
	if _, has := row.Tags["empty"]; has {
		t.Error("empty-valued tags must be dropped")
	}
	if row.Tags["tourism"] != "artwork" || row.Tags["artist_name"] != "Tomasz Moczek" {
		t.Errorf("category-source and extra tags must stay in JSONB: %v", row.Tags)
	}
}

func TestBuildRowPointResolution(t *testing.T) {
	way := overpass.Element{
		Type:   "way",
		ID:     7,
		Center: &overpass.Center{Lat: 51.10, Lon: 17.03},
		Tags:   map[string]string{"tourism": "museum"},
	}
	row, ok := BuildRow(way)
	if !ok || row.Lat != 51.10 || row.Lon != 17.03 {
		t.Fatalf("way center not used: %+v ok=%v", row, ok)
	}
	if row.OSMID != "way/7" {
		t.Fatalf("osm_id = %q; want way/7", row.OSMID)
	}

	node := overpass.Element{Type: "node", ID: 1, Lat: 51.2, Lon: 17.1, Tags: map[string]string{"tourism": "museum"}}
	row, ok = BuildRow(node)
	if !ok || row.Lat != 51.2 || row.Lon != 17.1 {
		t.Fatalf("node lat/lon not used: %+v ok=%v", row, ok)
	}
}

func TestBuildRowSkipsWayWithoutCenter(t *testing.T) {
	way := overpass.Element{Type: "way", ID: 9, Tags: map[string]string{"tourism": "museum"}}
	if _, ok := BuildRow(way); ok {
		t.Fatal("a way with no center must be skipped")
	}
}

func TestBuildRowSkipsUnmatched(t *testing.T) {
	node := overpass.Element{Type: "node", ID: 5, Lat: 51.1, Lon: 17.0, Tags: map[string]string{"amenity": "bench"}}
	if _, ok := BuildRow(node); ok {
		t.Fatal("an unmatched element must be skipped")
	}
}

func TestBuildRowSkipsNodeWithoutCoords(t *testing.T) {
	// A matching node with no valid lat/lon must be skipped, not planted on
	// null-island (0,0) — symmetric to a way with no center.
	node := overpass.Element{Type: "node", ID: 6, Tags: map[string]string{"tourism": "museum"}}
	if _, ok := BuildRow(node); ok {
		t.Fatal("a node with no coordinates must be skipped")
	}
}
