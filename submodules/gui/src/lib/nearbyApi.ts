import { apiFetch } from '@/lib/apiFetch'

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

/**
 * Thin, typed boundary over the spatial backend's nearby query. The URL is
 * origin-relative (`/api/spatial/...`) so the gui nginx reverse-proxies it to
 * the gateway — no base URL, no CORS. `categories` goes as a CSV of slugs.
 * Routed through {@link apiFetch} so it carries the Bearer token and gets the
 * 401 → silent-refresh → retry treatment.
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

  const response = await apiFetch(`/api/spatial/locations/nearby?${query.toString()}`, {
    signal: params.signal,
  })
  if (!response.ok) {
    throw new Error(`Nearby request failed: ${response.status}`)
  }
  return (await response.json()) as NearbyResponse
}
