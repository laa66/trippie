import { refreshAccessToken } from '@/lib/authClient'
import { getAccessToken } from '@/lib/authStore'

/**
 * fetch wrapper for the same-origin API. It:
 *  - attaches `Authorization: Bearer <access token>` to same-origin `/api/**` requests only (never
 *    to third-party URLs), so nearbyApi and every other protected call carry the token;
 *  - on a 401 from a protected call, runs a single-flight refresh (concurrent 401s share it) and
 *    retries the original request exactly once with the new token;
 *  - gives up on a failed refresh (the session is already cleared + the logged-out signal flipped),
 *    returning the original 401 without looping.
 *
 * The refresh call itself uses a raw fetch (authClient.doRefresh), not this wrapper, so a 401 on
 * /refresh can never re-enter the retry path.
 */
export async function apiFetch(input: string | URL, init: RequestInit = {}): Promise<Response> {
  const protectedCall = isProtectedApiUrl(input.toString())

  const send = (): Promise<Response> => {
    if (!protectedCall) {
      return fetch(input, init)
    }
    const headers = new Headers(init.headers)
    const token = getAccessToken()
    if (token !== null) {
      headers.set('Authorization', `Bearer ${token}`)
    }
    return fetch(input, { ...init, headers })
  }

  const response = await send()
  if (response.status !== 401 || !protectedCall) {
    return response
  }

  const refreshed = await refreshAccessToken()
  if (!refreshed) {
    return response
  }
  return send()
}

/** True only for same-origin `/api/**` URLs — the surface the Bearer token may ride on. */
function isProtectedApiUrl(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.origin)
    return parsed.origin === window.location.origin && parsed.pathname.startsWith('/api/')
  } catch {
    return false
  }
}
