import { apiFetchJson } from '@/lib/apiFetch'

/**
 * Cap on one batch, mirroring the server's `UnlockLocationsController.MAX_OSM_IDS` — more than this
 * is a 400, not a truncation. The client splits nothing: a batch exists to TIGHTEN the grant (one
 * position claim, one radius), so the overflow is left to the next fix rather than sent as a second
 * request against the same stale position.
 */
export const MAX_UNLOCK_BATCH = 50

/** One asserted position plus the POIs it claims to reach. The subject is the JWT's, never sent. */
export interface UnlockRequest {
  lat: number
  lon: number
  /** Required and positive by contract — absent is a 400, and > 100 a 422 for the whole request. */
  accuracyMeters: number
  osmIds: string[]
}

export interface UnlockResponse {
  /** POIs unlocked for this user AFTERWARDS, including ones that already were. */
  unlocked: string[]
  /** Strictly "too far away, or no such POI". No reason codes, and not an error. */
  refused: string[]
}

const UNLOCK_URL = '/api/spatial/locations/unlock'

/** Boundary check, exactly the pattern of nearbyApi.asNearby: the body is untrusted until it has
 *  the contract shape. Without it a `{}` would land `undefined` where the caller iterates to move
 *  POIs into known-unlocked, so an unlock would silently fail to stick and the POI would be offered
 *  again on every fix. */
function asUnlockResponse(body: unknown): UnlockResponse {
  const b = body as Partial<UnlockResponse> | null
  if (b === null || typeof b !== 'object' || !isStringArray(b.unlocked) || !isStringArray(b.refused)) {
    throw new Error('Malformed unlock response')
  }
  return b as UnlockResponse
}

function isStringArray(value: unknown): boolean {
  return Array.isArray(value) && value.every((v) => typeof v === 'string')
}

/**
 * Thin, typed boundary over the spatial unlock endpoint. Origin-relative so the gui nginx proxies
 * it to the gateway, and routed through {@link apiFetchJson} so it carries the Bearer token, gets
 * the 401 → silent-refresh → retry treatment and is bounded per attempt including the body read.
 * A non-2xx surfaces as an AuthApiError carrying the status (400 malformed batch, 422 accuracy too
 * coarse); `refused` ids are a 200, because being out of range is an answer, not a failure.
 */
export async function postUnlock(request: UnlockRequest, signal?: AbortSignal): Promise<UnlockResponse> {
  return asUnlockResponse(
    await apiFetchJson(UNLOCK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal,
    }),
  )
}
