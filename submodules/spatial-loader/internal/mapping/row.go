package mapping

import (
	"fmt"

	"github.com/laa66/trippie/spatial-loader/internal/overpass"
)

// Row is one location_point record. Name and Description are pointers so an
// absent OSM tag becomes SQL NULL rather than an empty string. Tags holds every
// OSM tag except the ones promoted to dedicated columns (name, description).
type Row struct {
	OSMID       string
	Name        *string
	Description *string
	Category    Category
	Lat         float64
	Lon         float64
	Tags        map[string]string
}

// BuildRow maps a parsed element to a Row. ok is false when the element cannot
// become a row — no category match, or no resolvable point — and the caller
// counts it as skipped. osm_id is "<type>/<id>".
func BuildRow(e overpass.Element) (Row, bool) {
	category, ok := ResolveCategory(e.Tags)
	if !ok {
		return Row{}, false
	}
	lat, lon, ok := point(e)
	if !ok {
		return Row{}, false
	}

	tags := make(map[string]string, len(e.Tags))
	for k, v := range e.Tags {
		if v == "" || k == "name" || k == "description" {
			continue
		}
		tags[k] = v
	}

	return Row{
		OSMID:       fmt.Sprintf("%s/%d", e.Type, e.ID),
		Name:        optional(e.Tags, "name"),
		Description: optional(e.Tags, "description"),
		Category:    category,
		Lat:         lat,
		Lon:         lon,
		Tags:        tags,
	}, true
}

// point resolves the representative coordinate: ways and relations use their
// `out center`, nodes use their own lat/lon.
func point(e overpass.Element) (lat, lon float64, ok bool) {
	switch e.Type {
	case "way", "relation":
		if e.Center == nil {
			return 0, 0, false
		}
		return e.Center.Lat, e.Center.Lon, true
	default:
		if e.Lat == 0 && e.Lon == 0 {
			return 0, 0, false
		}
		return e.Lat, e.Lon, true
	}
}

func optional(tags map[string]string, key string) *string {
	if v, ok := tags[key]; ok && v != "" {
		return &v
	}
	return nil
}
