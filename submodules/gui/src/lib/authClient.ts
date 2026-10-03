import { clearSession, getAccessToken, isLoggedOutLatched, markLoggedOut, setAccessToken } from '@/lib/authStore'

/**
 * Auth API client (flows 02 + 04). All calls are origin-relative so the gui nginx reverse-proxies
 * them to the gateway/auth service — no base URL, no CORS. The refresh token is an httpOnly cookie
 * the browser attaches automatically; the access token comes back in the JSON body and is held in
 * memory only (authStore).
 */

const AUTH_BASE = '/api/auth'
const CSRF_COOKIE = 'csrf'
const CSRF_HEADER = 'X-CSRF-Token'

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

async function postJson(path: string, body: unknown): Promise<Response> {
  const res = await fetch(`${AUTH_BASE}${path}`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return res
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
  const res = await postJson('/login', { email, password })
  const token = accessTokenOf(await res.json())
  if (token === null) {
    throw new Error('Login response had no access token')
  }
  setAccessToken(token)
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
  return fetch(`${AUTH_BASE}/logout`, { method: 'POST', credentials: 'same-origin', headers })
}

/**
 * Logout (flow 05). Raw fetch, not apiFetch: /refresh rotates the csrf cookie, so a retry must
 * re-read both the token and the csrf cookie (apiFetch would resend the stale header). An expired
 * access token 401s at the gateway before the logout is processed, so on a 401 refresh once and
 * resend — otherwise the server-side session would survive. The local session is cleared whatever
 * the server answers, and the latch/epoch stop any straggling refresh from resurrecting it.
 */
export async function logout(): Promise<void> {
  try {
    const res = await sendLogout()
    if (res.status === 401 && (await refreshAccessToken())) {
      await sendLogout()
    }
  } catch {
    // network failure: the local session is still dropped below
  } finally {
    epoch++
    inFlightRefresh = null
    sessionResolved = Promise.resolve(false)
    markLoggedOut()
    clearSession()
  }
}

/**
 * The raw refresh POST (flow 04). NEVER routed through apiFetch — a 401 here must not trigger
 * another refresh (no loop). Reads the refresh token from the httpOnly cookie (sent automatically)
 * and echoes the csrf cookie in the X-CSRF-Token header for the double-submit check.
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
  try {
    const res = await fetch(`${AUTH_BASE}/refresh`, {
      method: 'POST',
      credentials: 'same-origin',
      headers,
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
  if (isLoggedOutLatched()) {
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
 */
export function ensureSessionResolved(): Promise<boolean> {
  sessionResolved ??= refreshAccessToken()
  return sessionResolved
}
