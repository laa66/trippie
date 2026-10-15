/** Backend hard cap on the nearby query radius; the client clamps to it so requests never 400. */
export const MAX_RADIUS_METERS = 5000

/**
 * Below this zoom the viewport spans several kilometres, so a radius-capped
 * query would return a dense, mostly-off-screen result the user can't act on.
 * 12 is roughly a whole-city view (Wrocław fits the screen); we only query at
 * neighbourhood scale and closer.
 */
export const MIN_QUERY_ZOOM = 12

/**
 * Proximity radius for unlocking a POI. Same convention as the category contract: the SERVER
 * enforces it (one `ST_DWithin(..., 50)` inside the insert) and the client keeps a copy only to
 * skip a round-trip that is certain to be refused.
 *
 * The edge is EXCLUSIVE — `< 50`, never `<= 50`. Measured in M3-04: `ST_DWithin(..., 50)` is
 * false at exactly 50.0 m and the threshold bites at 49.9999.
 *
 * Accepted outright, not worked around: the client measures with {@link haversineMeters} on a
 * sphere while the server measures with `ST_Distance` on the WGS84 spheroid, a ~15 cm disagreement
 * at this radius. So the two WILL disagree exactly at the edge, and a POI the client offered being
 * `refused` is a NORMAL outcome there, not a failure — callers must neither surface it as an error
 * nor retry it in place.
 */
export const UNLOCK_RADIUS_METERS = 50

/**
 * Worst fix accuracy a proximity unlock may be granted at. Two gates on purpose: this one saves a
 * pointless round-trip, while the server stays the point of enforcement (`422` for the whole
 * request) and survives any client regression.
 *
 * Inclusive — `<= 100` passes, so 100 is still a candidate and 101 is not, matching the server's
 * `accuracy > 100` rejection.
 */
export const MAX_ACCURACY_METERS = 100

const EARTH_RADIUS_METERS = 6_371_000

interface LatLon {
  lat: number
  lon: number
}

function toRadians(deg: number): number {
  return (deg * Math.PI) / 180
}

/** Great-circle distance between two lat/lon points, in metres. */
export function haversineMeters(a: LatLon, b: LatLon): number {
  const dLat = toRadians(b.lat - a.lat)
  const dLon = toRadians(b.lon - a.lon)
  const lat1 = toRadians(a.lat)
  const lat2 = toRadians(b.lat)

  const sinDLat = Math.sin(dLat / 2)
  const sinDLon = Math.sin(dLon / 2)
  const h = sinDLat * sinDLat + Math.cos(lat1) * Math.cos(lat2) * sinDLon * sinDLon
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)))
}

/**
 * Query radius derived from the viewport: the centre→corner great-circle
 * distance, capped at MAX_RADIUS_METERS and rounded to an integer (the backend
 * takes an int meter value).
 */
export function viewportRadiusMeters(center: LatLon, corner: LatLon): number {
  return Math.round(Math.min(haversineMeters(center, corner), MAX_RADIUS_METERS))
}
