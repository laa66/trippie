import { AuthApiError, problemOf, refreshAccessToken } from '@/lib/authClient'
import { getAccessToken } from '@/lib/authStore'
import { bounded } from '@/lib/bounded'

/** Per-ATTEMPT budget. The chain 401 → refresh → retry is strictly additive, so a call can hold its
 *  caller for ~3x this: capping the total would reintroduce the dead post-refresh retry this budget
 *  exists to kill (accepted, and pinned by a test so the figure cannot drift). */
const ATTEMPT_TIMEOUT_MS = 10_000

type Attempt<T> = { ok: true; body: T } | { ok: false; status: number; error: AuthApiError }

/**
 * The JSON boundary for the same-origin API. One call is up to two attempts, each under its OWN
 * {@link ATTEMPT_TIMEOUT_MS} window covering the fetch AND the body read. It:
 *  - attaches `Authorization: Bearer <access token>` to same-origin `/api/**` requests only (never
 *    to third-party URLs), so nearbyApi and every other protected call carry the token;
 *  - on a 401 from a protected call, runs a single-flight refresh (concurrent 401s share it) and
 *    retries the original request exactly once with the new token, under a FRESH window — the
 *    refresh carries its own 10 s budget, so a shared one would hand the retry whatever remained of
 *    it, i.e. nothing at all after a slow-but-successful refresh;
 *  - gives up on a failed refresh (the session is already cleared + the logged-out signal flipped),
 *    throwing the original 401 without looping.
 *
 * It returns parsed JSON rather than a `Response` precisely so the body read falls inside the
 * window: a body that never streams wedges its caller exactly like headers that never arrive, and a
 * caller reading the body after the window closed would be unbounded again. `T` is an unchecked
 * cast — only the caller knows its contract, so the shape check belongs there (asSettings/asNearby).
 *
 * The refresh call itself uses a raw fetch (authClient.doRefresh), not this wrapper, so a 401 on
 * /refresh can never re-enter the retry path. That leg is NOT composed with `init.signal`, so a
 * caller abort during a refresh is honoured only when the retry starts; what stops a refresh the
 * caller abandoned from resurrecting a session is doRefresh's epoch guard, not the abort.
 *
 * @throws AuthApiError on any non-2xx, carrying the status plus the RFC-7807 type/detail when the
 *         error body had them — the value the callers used to build themselves via `problemOf`.
 * @throws the abort reason (`AbortError` / `TimeoutError`) when the caller cancels or the window
 *         expires, at any point including mid-body-read, on both the ok and the non-ok path.
 */
export async function apiFetchJson<T = unknown>(input: string | URL, init: RequestInit = {}): Promise<T> {
  const protectedCall = isProtectedApiUrl(input.toString())
  const callerSignal = init.signal ?? undefined

  const attempt = (): Promise<Attempt<T>> =>
    bounded(
      ATTEMPT_TIMEOUT_MS,
      async (signal) => {
        const headers = new Headers(init.headers)
        if (protectedCall) {
          const token = getAccessToken()
          if (token !== null) {
            headers.set('Authorization', `Bearer ${token}`)
          }
        }
        const res = await fetch(input, { ...init, headers, signal })
        if (res.ok) {
          return { ok: true, body: (await res.json()) as T }
        }
        const error = await problemOf(res)
        // problemOf swallows a failed body read by design (the status alone still drives the UI), so
        // an abort mid-error-body would come back as a plain non-2xx and a query the caller itself
        // cancelled would read as a server failure. Same discriminator as postJson's body read.
        if (signal.aborted) {
          throw signal.reason
        }
        return { ok: false, status: res.status, error }
      },
      callerSignal,
    )

  let outcome = await attempt()
  if (!outcome.ok && outcome.status === 401 && protectedCall && (await refreshAccessToken())) {
    outcome = await attempt()
  }
  if (!outcome.ok) {
    throw outcome.error
  }
  return outcome.body
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
