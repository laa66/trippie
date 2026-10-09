/**
 * Durable pending-logout marker (M2-19, criteria 6-13). When a logout's server leg does not answer
 * 204, nothing is known to have been revoked: the httpOnly refresh cookie may well still be alive,
 * and `authStore`'s logged-out latch dies with the reload — so the next boot's silent refresh would
 * resurrect the session on what may be a shared device. This marker survives the reload and tells
 * the next boot to replay the logout instead of refreshing.
 *
 * It stores a TIMESTAMP AND NOTHING ELSE — no access token, no `sub`, no email. That is what keeps
 * the memory-only-token decision intact: the marker is a purely NEGATIVE capability, it can only
 * deny a refresh, never grant one, and deleting it returns the client to its pre-M2-19 behaviour
 * rather than to something worse. It is NOT the security control either; the authoritative half is
 * the server refusing the surviving refresh token once the replay reaches it.
 *
 * This module is the single point of contact with `localStorage` in the auth path. Every read and
 * write is try/catch-wrapped (criterion 12): storage throws on access in a Safari private window
 * and wherever site data is blocked, and a boot that cannot read a marker must behave like a boot
 * with no marker, never crash.
 */

const MARKER_KEY = 'trippie.pendingLogout'

/** Records that a logout leg did not confirm with a 204. Timestamp only; overwrites a prior mark. */
export function markPendingLogout(): void {
  try {
    localStorage.setItem(MARKER_KEY, String(Date.now()))
  } catch {
    // storage unavailable: the marker is best-effort, the in-memory session is dropped regardless
  }
}

/** @returns true when a previous logout is still unconfirmed. Unreadable storage reads as "no marker". */
export function hasPendingLogout(): boolean {
  try {
    const marked = localStorage.getItem(MARKER_KEY)
    return marked !== null && marked !== ''
  } catch {
    return false
  }
}

/** Drops the marker. Called ONLY on a 204 replay (criterion 8) or a successful login (criterion 11). */
export function clearPendingLogout(): void {
  try {
    localStorage.removeItem(MARKER_KEY)
  } catch {
    // storage unavailable: nothing was ever stored to clear
  }
}
