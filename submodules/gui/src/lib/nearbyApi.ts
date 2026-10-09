import { apiFetchJson } from '@/lib/apiFetch'

export interface NearbyItem {
  id: string
  name: string | null
  category: string
  lat: number
  lon: number
  distanceMeters: number
}

export interface NearbyResponse {
  items: NearbyItem[]
  truncated: boolean
}

export interface FetchNearbyParams {
  lat: number
  lon: number
  radius: number
  categories: string[]
  limit?: number
  signal?: AbortSignal
}

/** Boundary check: the server body is untrusted until it has the contract shape. Without it a `{}`
 *  body lands `undefined` in useNearbyPois' refs and a `null` body throws a TypeError into its
 *  catch, both read as "the query failed" one layer too late. Mirrors settingsApi.asSettings. */
function asNearby(body: unknown): NearbyResponse {
  const b = body as Partial<NearbyResponse> | null
  if (b === null || typeof b !== 'object' || typeof b.truncated !== 'boolean' || !Array.isArray(b.items) || !b.items.every(isNearbyItem)) {
    throw new Error('Malformed nearby response')
  }
  return b as NearbyResponse
}

function isNearbyItem(item: unknown): boolean {
  const i = item as Partial<NearbyItem> | null
  return (
    i !== null &&
    typeof i === 'object' &&
    typeof i.id === 'string' &&
    typeof i.category === 'string' &&
    (i.name === null || typeof i.name === 'string') &&
    typeof i.lat === 'number' &&
    typeof i.lon === 'number' &&
    typeof i.distanceMeters === 'number'
  )
}

/**
 * Thin, typed boundary over the spatial backend's nearby query. The URL is
 * origin-relative (`/api/spatial/...`) so the gui nginx reverse-proxies it to
 * the gateway — no base URL, no CORS. `categories` goes as a CSV of slugs.
 * Routed through {@link apiFetchJson} so it carries the Bearer token, gets the
 * 401 → silent-refresh → retry treatment, and is bounded per attempt (fetch +
 * body read). A non-2xx surfaces as an AuthApiError carrying the status.
 * The 2xx body is shape-checked here before it reaches the composables.
 */
export async function fetchNearby(params: FetchNearbyParams): Promise<NearbyResponse> {
  const query = new URLSearchParams({
    lat: String(params.lat),
    lon: String(params.lon),
    radius: String(params.radius),
    categories: params.categories.join(','),
  })
  if (params.limit !== undefined) {
    query.set('limit', String(params.limit))
  }

  // The shape check lives here, not in apiFetchJson: its `T` is a cast, and only this caller knows
  // the contract.
  return asNearby(
    await apiFetchJson(`/api/spatial/locations/nearby?${query.toString()}`, { signal: params.signal }),
  )
}
