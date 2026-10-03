import { readonly, ref, type Ref } from 'vue'

/**
 * In-memory access-token store (module singleton). The access token lives ONLY here — it is never
 * written to localStorage/sessionStorage/cookies, so an XSS payload cannot lift it from web storage
 * and it dies with the tab. Persistence across reloads is the httpOnly refresh cookie's job: boot
 * calls a silent refresh to repopulate this store (see authClient.ensureSessionResolved).
 *
 * `isAuthenticated` is the reactive logged-in/out signal route-guards (M2-15) can observe. It is
 * exposed read-only; only setAccessToken/clearSession flip it.
 */

let accessToken: string | null = null
const authenticated = ref(false)
let loggedOutLatch = false

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string): void {
  accessToken = token
  authenticated.value = true
  loggedOutLatch = false
}

/** Clear the in-memory token and flip the signal to logged-out. */
export function clearSession(): void {
  accessToken = null
  authenticated.value = false
}

/** Set by an explicit logout so a straggler refresh cannot resurrect the session; setAccessToken resets it. */
export function markLoggedOut(): void {
  loggedOutLatch = true
}

export function isLoggedOutLatched(): boolean {
  return loggedOutLatch
}

export interface UseAuthStore {
  /** Reactive logged-in/out signal. Read-only; mutated only via the auth client. */
  isAuthenticated: Readonly<Ref<boolean>>
}

export function useAuthStore(): UseAuthStore {
  return { isAuthenticated: readonly(authenticated) }
}
