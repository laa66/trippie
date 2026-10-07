import { apiFetch } from '@/lib/apiFetch'
import { problemOf } from '@/lib/authClient'

export const CONTENT_MODES = ['TEXT', 'AUDIO', 'BOTH'] as const
export type ContentMode = (typeof CONTENT_MODES)[number]

export interface UserSettings {
  defaultContentMode: ContentMode
  selectedCategories: string[]
}

const SETTINGS_URL = '/api/auth/settings'
const TIMEOUT_MS = 10_000

/** A hung request must not wedge the caller: this bounds the settings request itself, on top of the caller's
 *  own cancellation. It does NOT bound a 401 retry's /refresh leg (a separate fetch, awaited inside this
 *  one's await) — authClient.doRefresh carries its own timeout for that.
 *  Not AbortSignal.any: it needs Safari 17.4, and the Capacitor wrapper runs on the OS WebView. */
function withTimeout(signal?: AbortSignal): AbortSignal {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new DOMException('timeout', 'TimeoutError')), TIMEOUT_MS)
  signal?.addEventListener(
    'abort',
    () => {
      clearTimeout(timer)
      controller.abort(signal.reason)
    },
    { once: true },
  )
  return controller.signal
}

/** Boundary check: the server body is untrusted until it has the contract shape. */
function asSettings(body: unknown): UserSettings {
  const b = body as Partial<UserSettings> | null
  if (
    b === null ||
    typeof b !== 'object' ||
    !(CONTENT_MODES as readonly unknown[]).includes(b.defaultContentMode) ||
    !Array.isArray(b.selectedCategories) ||
    !b.selectedCategories.every((c) => typeof c === 'string')
  ) {
    throw new Error('Malformed settings response')
  }
  return b as UserSettings
}

/** Protected endpoints: via apiFetch (Bearer + 401 refresh). The user is the JWT subject, never sent. */
export async function getSettings(signal?: AbortSignal): Promise<UserSettings> {
  const res = await apiFetch(SETTINGS_URL, { signal: withTimeout(signal) })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return asSettings(await res.json())
}

/** Returns the settings as the server stored them; callers must use this, not their optimistic value. */
export async function putSettings(settings: UserSettings, signal?: AbortSignal): Promise<UserSettings> {
  const res = await apiFetch(SETTINGS_URL, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
    signal: withTimeout(signal),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return asSettings(await res.json())
}
