import { apiFetch } from '@/lib/apiFetch'
import { problemOf } from '@/lib/authClient'

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

/** Protected endpoints: via apiFetch (Bearer + 401 refresh). The user is the JWT subject, never sent. */
export async function getSettings(): Promise<UserSettings> {
  const res = await apiFetch(SETTINGS_URL)
  if (!res.ok) {
    throw await problemOf(res)
  }
  return asSettings(await res.json())
}

/** Returns the settings as the server stored them; callers must use this, not their optimistic value. */
export async function putSettings(settings: UserSettings): Promise<UserSettings> {
  const res = await apiFetch(SETTINGS_URL, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
  if (!res.ok) {
    throw await problemOf(res)
  }
  return asSettings(await res.json())
}
