// Package mapping turns parsed Overpass elements into location_point rows: it
// derives the POI category from OSM tags and splits an element into dedicated
// columns versus the tags JSONB. Fetching lives in package overpass (M1-02);
// the upsert lives in package loader (M1-03).
package mapping

import "github.com/laa66/trippie/spatial-loader/internal/osmtags"

// Category is one of the eight slugs the location_point CHECK constraint
// enforces. ResolveCategory only ever emits these — the DB CHECK is the drift
// guard, so this list must stay in lockstep with the migration.
type Category string

const (
	PublicArt    Category = "public_art"
	Monuments    Category = "monuments"
	Heritage     Category = "heritage"
	Sacred       Category = "sacred"
	Museums      Category = "museums"
	Viewpoints   Category = "viewpoints"
	Architecture Category = "architecture"
	Attractions  Category = "attractions"
)

// sacredBuildings is the O(1) lookup for building=* values that are places of
// worship even without an amenity=place_of_worship tag. It is derived from
// osmtags.SacredBuildings (the shared contract) so it cannot drift from the
// query's building regex.
var sacredBuildings = buildSet(osmtags.SacredBuildings)

func buildSet(values []string) map[string]struct{} {
	set := make(map[string]struct{}, len(values))
	for _, v := range values {
		set[v] = struct{}{}
	}
	return set
}

// tourismCategories maps each shared tourism=* value to its category. The value
// vocabulary is single-sourced from osmtags.TourismPOIValues (a build-time check
// guarantees every value there has a category here), and each value literal is
// an osmtags constant, so the query regex and these rules cannot drift.
var tourismCategories = map[string]Category{
	osmtags.TourismArtwork:    PublicArt,
	osmtags.TourismMuseum:     Museums,
	osmtags.TourismViewpoint:  Viewpoints,
	osmtags.TourismAttraction: Attractions,
}

func init() {
	for _, v := range osmtags.TourismPOIValues {
		if _, ok := tourismCategories[v]; !ok {
			panic("mapping: osmtags.TourismPOIValues member " + v + " has no category rule")
		}
	}
}

// rule pairs a category with a predicate over an element's OSM tags.
type rule struct {
	category  Category
	predicate func(tags map[string]string) bool
}

// rules is the ordered first-match list. A feature can satisfy several
// predicates (e.g. historic=monument + tourism=attraction); the first match
// wins, which is the most specific category. Order is frozen by PLAN.md:
// public_art -> monuments -> heritage -> sacred -> museums -> viewpoints ->
// architecture -> attractions.
var rules = []rule{
	{PublicArt, tourismIs(PublicArt)},
	{Monuments, func(t map[string]string) bool {
		return t[osmtags.KeyHistoric] == "monument" || t[osmtags.KeyHistoric] == "memorial"
	}},
	{Heritage, func(t map[string]string) bool {
		return has(t, osmtags.KeyHistoric) || has(t, osmtags.KeyHeritage)
	}},
	{Sacred, func(t map[string]string) bool {
		if t[osmtags.KeyAmenity] == osmtags.ValuePlaceOfWorship {
			return true
		}
		_, ok := sacredBuildings[t[osmtags.KeyBuilding]]
		return ok
	}},
	{Museums, tourismIs(Museums)},
	{Viewpoints, tourismIs(Viewpoints)},
	{Architecture, func(t map[string]string) bool {
		return has(t, osmtags.KeyArchitect) || has(t, osmtags.KeyBuildingArchitecture)
	}},
	{Attractions, tourismIs(Attractions)},
}

// tourismIs builds a predicate matching elements whose tourism=* value resolves
// to cat. It reads the value through tourismCategories, so the tourism
// vocabulary stays single-sourced in osmtags.
func tourismIs(cat Category) func(map[string]string) bool {
	return func(t map[string]string) bool {
		got, ok := tourismCategories[t[osmtags.KeyTourism]]
		return ok && got == cat
	}
}

// ResolveCategory returns the highest-priority category a feature matches. The
// bool is false when no rule matches — the caller skips and counts the feature.
func ResolveCategory(tags map[string]string) (Category, bool) {
	for _, r := range rules {
		if r.predicate(tags) {
			return r.category, true
		}
	}
	return "", false
}

func has(tags map[string]string, key string) bool {
	return tags[key] != ""
}
