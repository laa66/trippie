import { apiFetchJson } from '@/lib/apiFetch'

export const CONTENT_MODES = ['TEXT', 'AUDIO', 'BOTH'] as const
export type ContentMode = (typeof CONTENT_MODES)[number]

export interface UserSettings {
  defaultContentMode: ContentMode
  selectedCategories: string[]
}

const SETTINGS_URL = '/api/auth/settings'

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

/** Protected endpoints: via apiFetchJson (Bearer + 401 refresh + the per-attempt timeout that
 *  covers the body read). The user is the JWT subject, never sent. `signal` is the caller's
 *  cancellation only — the session controller in useCategorySelection, so no request outlives the
 *  session that started it. */
export async function getSettings(signal?: AbortSignal): Promise<UserSettings> {
  return asSettings(await apiFetchJson(SETTINGS_URL, { signal }))
}

/** Returns the settings as the server stored them; callers must use this, not their optimistic value. */
export async function putSettings(settings: UserSettings, signal?: AbortSignal): Promise<UserSettings> {
  return asSettings(
    await apiFetchJson(SETTINGS_URL, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(settings),
      signal,
    }),
  )
}
