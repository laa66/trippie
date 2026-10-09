import { clearSession, getAccessToken, isLoggedOutLatched, markLoggedOut, setAccessToken } from '@/lib/authStore'
import { bounded } from '@/lib/bounded'
import { clearPendingLogout, hasPendingLogout, markPendingLogout } from '@/lib/pendingLogout'

/**
 * Auth API client (flows 02 + 04). All calls are origin-relative so the gui nginx reverse-proxies
 * them to the gateway/auth service — no base URL, no CORS. The refresh token is an httpOnly cookie
 * the browser attaches automatically; the access token comes back in the JSON body and is held in
 * memory only (authStore).
 */

const AUTH_BASE = '/api/auth'
const CSRF_COOKIE = 'csrf'
const CSRF_HEADER = 'X-CSRF-Token'
// A hung leg must not keep its caller suspended: every auth fetch is bounded. 10 s where the caller is
// waiting on the answer (the forms behind a spinner; refresh on the critical path of every retry), 5 s
// for logout, whose on-screen outcome depends only on the local finally, not on the server's reply.
const API_TIMEOUT_MS = 10_000
const LOGOUT_TIMEOUT_MS = 5_000

/** Extract a non-empty access token from a 2xx auth response body, or null if the boundary lied. */
function accessTokenOf(body: unknown): string | null {
  if (typeof body === 'object' && body !== null) {
    const token = (body as { accessToken?: unknown }).accessToken
    if (typeof token === 'string' && token.length > 0) {
      return token
    }
  }
  return null
}

/**
 * Read the double-submit CSRF token from `document.cookie` to echo it into the {@link CSRF_HEADER}.
 *
 * CONTRACT: the auth service sets the `csrf` cookie with `Path=/` (JS-readable, non-httpOnly) so the
 * SPA at origin root `/` can read it here; the refresh cookie stays httpOnly `Path=/api/auth`. This
 * helper is the single isolation point for that cookie dependency.
 */
export function readCsrfToken(): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${CSRF_COOKIE}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * Drops the `csrf` cookie after a logout the server did not confirm (M2-19 criterion 6). It defends
 * only against an unwitting NEXT user of the device: the double-submit token is stateless and
 * client-forgeable by design (auth stores nothing, it only compares header to cookie), so this is
 * never a defence against devtools on that same device.
 *
 * Path must repeat the one auth set it with (`/`), or the browser drops nothing.
 */
function deleteCsrfCookie(): void {
  document.cookie = `${CSRF_COOKIE}=; Path=/; Max-Age=0`
}

/**
 * Mints a fresh random `csrf` cookie and returns its value, for the boot-time logout replay to echo
 * (M2-19 criterion 7). Legitimate precisely because the double-submit check is stateless: auth
 * asserts header == cookie and nothing more, so a self-minted pair is as valid as a server-minted
 * one.
 *
 * `Secure` tracks the scheme instead of being hardcoded either way, mirroring auth's own
 * `auth.cookies.secure` knob (default true, flipped to false only for the local http stack): on http
 * a `Secure` cookie would be dropped and the replay would 403, while on https a cookie minted
 * WITHOUT `Secure` would overwrite the server's `Secure` one with a weaker variant.
 */
function mintCsrfCookie(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  const value = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  const secure = location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${CSRF_COOKIE}=${value}; Path=/; SameSite=Strict${secure}`
  return value
}

/** Stable ProblemDetail `type` the auth service returns on login for a correct-password, unverified account. */
export const EMAIL_NOT_VERIFIED_TYPE = 'urn:trippie:auth:email-not-verified'

/** A non-2xx auth response: HTTP status plus the RFC-7807 `type`/`detail` when the body had them. */
export class AuthApiError extends Error {
  readonly status: number
  readonly type: string | null
  readonly detail: string | null

  constructor(status: number, type: string | null, detail: string | null) {
    super(`Auth request failed: ${status}`)
    this.status = status
    this.type = type
    this.detail = detail
  }
}

export async function problemOf(res: Response): Promise<AuthApiError> {
  let type: string | null = null
  let detail: string | null = null
  try {
    const body = (await res.json()) as { type?: unknown; detail?: unknown } | null
    type = typeof body?.type === 'string' ? body.type : null
    detail = typeof body?.detail === 'string' ? body.detail : null
  } catch {
    // non-JSON error body: status alone still drives the UI
  }
  return new AuthApiError(res.status, type, detail)
}

/** @returns the parsed 2xx body, or null when there is none (the endpoints that answer with no content). */
async function postJson(path: string, body: unknown): Promise<unknown> {
  return bounded(API_TIMEOUT_MS, async (signal) => {
    const res = await fetch(`${AUTH_BASE}${path}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal,
    })
    if (!res.ok) {
      throw await problemOf(res)
    }
    // An ABORTED body read is not "2xx with no body": the signal in scope is the discriminator, so
    // the abort propagates and the caller sees a failure instead of a bogus success on a leg that
    // was cut off mid-stream. A genuinely empty or non-JSON body still resolves to null.
    try {
      return (await res.json()) as unknown
    } catch (e) {
      if (signal.aborted) {
        throw e
      }
      return null
    }
  })
}

export async function register(email: string, password: string): Promise<void> {
  await postJson('/register', { email, password })
}

export async function verifyEmail(email: string, code: string): Promise<void> {
  await postJson('/verify', { email, code })
}

export async function resendCode(email: string): Promise<void> {
  await postJson('/resend', { email })
}

export async function requestPasswordReset(email: string): Promise<void> {
  await postJson('/forgot-password', { email })
}

export async function resetPassword(email: string, code: string, newPassword: string): Promise<void> {
  await postJson('/reset-password', { email, code, newPassword })
}

/** Public login (flow 02). Sets the refresh + csrf cookies (server-side) and stores the access token. */
export async function login(email: string, password: string): Promise<void> {
  const token = accessTokenOf(await postJson('/login', { email, password }))
  if (token === null) {
    throw new Error('Login response had no access token')
  }
  setAccessToken(token)
  // A fresh session supersedes an unconfirmed old one (M2-19 criterion 11): without this a sticky
  // marker would deny every boot refresh after a re-login, i.e. a lockout.
  clearPendingLogout()
}

function sendLogout(): Promise<Response> {
  const headers: Record<string, string> = {}
  const token = getAccessToken()
  if (token !== null) {
    headers.Authorization = `Bearer ${token}`
  }
  const csrf = readCsrfToken()
  if (csrf !== null) {
    headers[CSRF_HEADER] = csrf
  }
  return bounded(LOGOUT_TIMEOUT_MS, (signal) =>
    fetch(`${AUTH_BASE}/logout`, { method: 'POST', credentials: 'same-origin', headers, signal }),
  )
}

/**
 * Logout (flow 05). Raw fetch, not apiFetch: /refresh rotates the csrf cookie, so a retry must
 * re-read both the token and the csrf cookie (apiFetch would resend the stale header). An expired
 * access token 401s at the gateway before the logout is processed, so on a 401 refresh once and
 * resend — otherwise the server-side session would survive. The local session is cleared whatever
 * the server answers, and the latch/epoch stop any straggling refresh from resurrecting it.
 *
 * <p>M2-19 criterion 6: any outcome that is not a 204 — a timeout, a thrown network error, a 4xx, a
 * 5xx — leaves the pending-logout marker behind, because a server answer other than 204 is no
 * evidence that the refresh family was revoked. The condition is deliberately BROADER than
 * ForgotPasswordView's local-failure branch and the two must NOT be unified: there a leg that never
 * reached the server is singled out because it REVEALS nothing about account existence; here a 5xx
 * is as dangerous as a timeout because it CONCEALS whether the session died.
 */
export async function logout(): Promise<void> {
  try {
    let res = await sendLogout()
    if (res.status === 401 && (await refreshAccessToken())) {
      res = await sendLogout()
    }
    if (res.status !== 204) {
      recordUnconfirmedLogout()
    }
  } catch {
    // timeout or network failure: nothing was confirmed revoked, so the replay must happen
    recordUnconfirmedLogout()
  } finally {
    epoch++
    inFlightRefresh = null
    sessionResolved = Promise.resolve(false)
    markLoggedOut()
    clearSession()
  }
}

function recordUnconfirmedLogout(): void {
  markPendingLogout()
  deleteCsrfCookie()
}

/**
 * Boot-time logout replay (M2-19 criteria 7 + 8). With the marker set this is the ONLY auth call the
 * boot makes — no `/refresh` whatsoever, because refreshing first would mint a live 15-minute access
 * token on what may be a shared device every time a logout had failed, i.e. the exact threat being
 * closed. The replay carries no `Authorization` header: it must be able to DESTROY a session it never
 * created, which is why auth accepts `/logout` on the refresh cookie + CSRF alone.
 *
 * Deliberately NOT routed through {@link postJson} (M2-19 criterion 8): the decision is read off
 * `res.status === 204` and nothing else — never off "the promise resolved" — and no body is read at
 * all, so no body-parsing policy can ever reinterpret a cut-off leg as a confirmed revocation.
 *
 * Any other outcome keeps the marker, so a repeatedly failing server never silently resurrects the
 * session. A sticky marker is the CORRECT end state, not a defect: it keeps the app logged out, and
 * {@link login} clears it, so there is no lockout.
 */
export async function resumePendingLogout(): Promise<void> {
  if (!hasPendingLogout()) {
    return
  }
  const csrf = mintCsrfCookie()
  try {
    const res = await bounded(LOGOUT_TIMEOUT_MS, (signal) =>
      fetch(`${AUTH_BASE}/logout`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { [CSRF_HEADER]: csrf },
        signal,
      }),
    )
    if (res.status === 204) {
      clearPendingLogout()
    }
  } catch {
    // marker stays: the app remains logged out and the next boot retries
  } finally {
    // Unconditional: a failed replay must not leave the cookie this function minted sitting in the
    // jar, since recordUnconfirmedLogout() deleted the server's one on purpose. The next boot mints
    // a fresh one anyway.
    deleteCsrfCookie()
  }
}

/**
 * The raw refresh POST (flow 04). NEVER routed through apiFetch — a 401 here must not trigger
 * another refresh (no loop). Reads the refresh token from the httpOnly cookie (sent automatically)
 * and echoes the csrf cookie in the X-CSRF-Token header for the double-submit check.
 *
 * Bounded by {@link API_TIMEOUT_MS}: apiFetch awaits this INSIDE the await its caller is
 * suspended on, so a hung /refresh would leave that caller's promise unsettled for the page's life
 * (its own timeout aborts a request that already returned its 401). A timeout settles as a failure,
 * i.e. the same path as a network error: the caller gets its original 401 back.
 *
 * @returns true if a new access token was obtained and stored; false on any failure (session cleared).
 */
async function doRefresh(): Promise<boolean> {
  const startedEpoch = epoch
  const stale = (): boolean => epoch !== startedEpoch
  // a stale (pre-logout) refresh must not touch the store at all, success or failure
  const fail = (): boolean => {
    if (!stale()) {
      clearSession()
    }
    return false
  }
  const csrf = readCsrfToken()
  const headers: Record<string, string> = {}
  if (csrf !== null) {
    headers[CSRF_HEADER] = csrf
  }
  return bounded(API_TIMEOUT_MS, async (signal) => {
    try {
      const res = await fetch(`${AUTH_BASE}/refresh`, {
        method: 'POST',
        credentials: 'same-origin',
        headers,
        signal,
      })
      if (!res.ok) {
        return fail()
      }
      const token = accessTokenOf(await res.json())
      if (token === null) {
        return fail()
      }
      if (stale()) {
        return false
      }
      setAccessToken(token)
      return true
    } catch {
      return fail()
    }
  })
}

let inFlightRefresh: Promise<boolean> | null = null
let sessionResolved: Promise<boolean> | null = null
// bumped on logout: a refresh that started before it must not store its token
let epoch = 0

/**
 * Single-flight refresh: concurrent callers (e.g. a burst of 401s) all await ONE in-flight refresh
 * promise, so exactly one `/refresh` request fires. The next refresh after this one settles starts
 * fresh — the guarantee is about concurrency, not lifetime.
 */
export function refreshAccessToken(): Promise<boolean> {
  // The choke point for BOTH logged-out signals, and the only place that is on the path of every
  // refresh (M2-19 HIGH-1). The in-memory latch dies with the reload; the pending-logout marker is
  // what survives it, and checking it only in ensureSessionResolved left a hole: an apiFetch 401
  // arriving before the nav guard had run would refresh and put a live token back in memory on a
  // device whose user believes they signed out. Without a marker this is a storage read and nothing
  // else — the no-marker path stays byte-identical (criterion 9).
  if (isLoggedOutLatched() || hasPendingLogout()) {
    return Promise.resolve(false)
  }
  if (inFlightRefresh !== null) {
    return inFlightRefresh
  }
  const pending = doRefresh().finally(() => {
    if (inFlightRefresh === pending) {
      inFlightRefresh = null
    }
  })
  inFlightRefresh = pending
  return pending
}

/**
 * Memoized, once-only boot refresh: the first call starts it, every later call (the nav guard on
 * each navigation; also started once at app boot) gets the same settled promise — unlike refreshAccessToken, which would fire a
 * fresh /refresh whenever nothing is in flight.
 *
 * With a pending-logout marker it resolves `false` IMMEDIATELY (M2-19 criterion 10) and issues no
 * request at all: the nav guard awaits this, and it must not stall behind the up-to-5 s replay that
 * {@link resumePendingLogout} is running in the background. With no marker the behaviour is
 * unchanged — one memoized `/refresh` (criterion 9).
 */
export function ensureSessionResolved(): Promise<boolean> {
  if (hasPendingLogout()) {
    // Both halves of "logged out", symmetrically: the latch refuses future refreshes, clearSession
    // drops a token that may already be in memory. The second matters for a tab that was alive when
    // another tab's logout failed — localStorage is shared, this tab's in-memory token is not, and
    // without it that tab would sail through the nav guard and carry the token for up to 15 min. On
    // a cold boot it is a no-op.
    markLoggedOut()
    clearSession()
    sessionResolved ??= Promise.resolve(false)
    return sessionResolved
  }
  sessionResolved ??= refreshAccessToken()
  return sessionResolved
}
