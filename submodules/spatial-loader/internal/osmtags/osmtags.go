// Package osmtags is the single source of truth for the OSM tag vocabulary
// shared by two independent consumers: the loader's category rules
// (internal/mapping) and the Overpass query builder (internal/overpass).
//
// Both sides used to hardcode their own copy of this vocabulary. They agreed by
// coincidence but could DRIFT — adding a sacred building value to the mapping
// without widening the query regex would silently drop those POIs server-side
// while every test stayed green. This leaf package (it imports nothing internal)
// removes that drift: the mapping derives its lookup sets from these slices, the
// query derives its regex alternations from them, and both reference the same
// key/value constants. Add a value here once and both sides pick it up.
//
// Slice order is fixed and meaningful: the query joins these into anchored regex
// alternations, so a stable order keeps the generated query deterministic.
package osmtags

// SacredBuildings are the building=* values that mark a place of worship even
// without an amenity=place_of_worship tag. The mapping builds its O(1) sacred
// lookup set from this slice; the query builds a "building" regex alternation
// from it. Order is fixed to keep the generated query deterministic.
var SacredBuildings = []string{
	"church",
	"chapel",
	"cathedral",
	"basilica",
	"monastery",
	"shrine",
	"mosque",
	"synagogue",
	"temple",
}

// TourismPOIValues are the tourism=* values that resolve to a category
// (artwork->public_art, museum->museums, viewpoint->viewpoints,
// attraction->attractions). The query builds a "tourism" regex alternation from
// this slice; the mapping single-sources its tourism value literals here. Order
// is fixed to keep the generated query deterministic.
var TourismPOIValues = []string{
	TourismArtwork,
	TourismMuseum,
	TourismViewpoint,
	TourismAttraction,
}

// Shared tag keys — used by BOTH the mapping predicates and the query clauses.
const (
	// KeyTourism is the tourism=* key: a value-matched clause on both sides.
	KeyTourism = "tourism"
	// KeyHistoric is the historic=* key: a presence clause on both sides.
	KeyHistoric = "historic"
	// KeyHeritage is the heritage=* key: a presence clause on both sides.
	KeyHeritage = "heritage"
	// KeyArchitect is the architect=* key: a presence clause on both sides.
	KeyArchitect = "architect"
	// KeyBuildingArchitecture is the building:architecture=* key: a presence
	// clause on both sides.
	KeyBuildingArchitecture = "building:architecture"
	// KeyBuilding is the building=* key: matched against SacredBuildings on
	// both sides.
	KeyBuilding = "building"
	// KeyAmenity is the amenity=* key: matched against ValuePlaceOfWorship on
	// both sides.
	KeyAmenity = "amenity"
)

// Shared tourism=* values that resolve to a category. Individually referenced by
// the mapping rules; collectively the vocabulary in TourismPOIValues.
const (
	TourismArtwork    = "artwork"
	TourismMuseum     = "museum"
	TourismViewpoint  = "viewpoint"
	TourismAttraction = "attraction"
)

// ValuePlaceOfWorship is the amenity=place_of_worship value: a sacred-category
// equality clause on both sides.
const ValuePlaceOfWorship = "place_of_worship"
