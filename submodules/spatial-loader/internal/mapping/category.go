// Package mapping turns parsed Overpass elements into location_point rows: it
// derives the POI category from OSM tags and splits an element into dedicated
// columns versus the tags JSONB. Fetching lives in package overpass (M1-02);
// the upsert lives in package loader (M1-03).
package mapping

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

// sacredBuildings are building=* values that are places of worship even without
// an amenity=place_of_worship tag.
var sacredBuildings = map[string]struct{}{
	"church":    {},
	"chapel":    {},
	"cathedral": {},
	"basilica":  {},
	"monastery": {},
	"shrine":    {},
	"mosque":    {},
	"synagogue": {},
	"temple":    {},
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
	{PublicArt, func(t map[string]string) bool { return t["tourism"] == "artwork" }},
	{Monuments, func(t map[string]string) bool {
		return t["historic"] == "monument" || t["historic"] == "memorial"
	}},
	{Heritage, func(t map[string]string) bool { return has(t, "historic") || has(t, "heritage") }},
	{Sacred, func(t map[string]string) bool {
		if t["amenity"] == "place_of_worship" {
			return true
		}
		_, ok := sacredBuildings[t["building"]]
		return ok
	}},
	{Museums, func(t map[string]string) bool { return t["tourism"] == "museum" }},
	{Viewpoints, func(t map[string]string) bool { return t["tourism"] == "viewpoint" }},
	{Architecture, func(t map[string]string) bool {
		return has(t, "architect") || has(t, "building:architecture")
	}},
	{Attractions, func(t map[string]string) bool { return t["tourism"] == "attraction" }},
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
