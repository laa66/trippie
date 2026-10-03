import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'

const STORAGE_KEY = 'trippie.selectedCategories'

function res(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

afterEach(() => vi.unstubAllGlobals())

async function boot(fetchMock: ReturnType<typeof vi.fn>) {
  vi.resetModules()
  const store = new Map([[STORAGE_KEY, JSON.stringify(['sacred'])]])
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
  })
  vi.stubGlobal('fetch', fetchMock)
  const authStore = await import('@/lib/authStore')
  authStore.setAccessToken('jwt-old')
  const { useCategorySelection } = await import('@/composables/useCategorySelection')
  await flushPromises()
  return { authStore, sel: useCategorySelection() }
}

describe('useCategorySelection with the real settingsApi + apiFetch', () => {
  it('a 401 on GET /settings refreshes, retries and hydrates', async () => {
    let settingsCalls = 0
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') return res(200, { accessToken: 'jwt-new' })
      settingsCalls++
      return settingsCalls === 1
        ? res(401, {})
        : res(200, { defaultContentMode: 'AUDIO', selectedCategories: ['museums'] })
    })

    const { sel, authStore } = await boot(fetchMock)

    expect(settingsCalls).toBe(2)
    expect(sel.selected.value).toEqual(['museums'])
    expect(authStore.getAccessToken()).toBe('jwt-new')
  })

  it('a 401 whose refresh is dead clears the session and falls back to localStorage', async () => {
    const fetchMock = vi.fn(async (url: string) => (url === '/api/auth/refresh' ? res(401, {}) : res(401, {})))

    const { sel, authStore } = await boot(fetchMock)

    expect(authStore.useAuthStore().isAuthenticated.value).toBe(false)
    expect(sel.selected.value).toEqual(['sacred'])
  })
})
