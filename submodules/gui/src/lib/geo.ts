/** Backend hard cap on the nearby query radius; the client clamps to it so requests never 400. */
export const MAX_RADIUS_METERS = 5000

/**
 * Below this zoom the viewport spans several kilometres, so a radius-capped
 * query would return a dense, mostly-off-screen result the user can't act on.
 * 12 is roughly a whole-city view (Wrocław fits the screen); we only query at
 * neighbourhood scale and closer.
 */
export const MIN_QUERY_ZOOM = 12

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
