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

  it('a 401 whose refresh is dead clears the session and restores the localStorage fallback over the hydrated server value', async () => {
    let refreshDead = false
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/auth/refresh') return res(401, {})
      if (init?.method === 'PUT') {
        refreshDead = true
        return res(401, {})
      }
      return res(200, { defaultContentMode: 'AUDIO', selectedCategories: ['museums'] })
    })

    const { sel, authStore } = await boot(fetchMock)
    expect(sel.selected.value).toEqual(['museums'])

    sel.toggle('monuments')
    await flushPromises()

    expect(refreshDead).toBe(true)
    expect(authStore.useAuthStore().isAuthenticated.value).toBe(false)
    expect(sel.selected.value).toEqual(['sacred'])
  })

  it('a settings GET that never settles times out after 10 s: error set, hydrating cleared, the next toggle retries the GET', async () => {
    vi.useFakeTimers()
    try {
      const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
        return new Promise<Response>((_resolve, reject) => {
          init!.signal!.addEventListener('abort', () => reject(init!.signal!.reason))
        })
      })
      const { sel } = await boot(fetchMock)
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(sel.error.value).toBeNull()

      await vi.advanceTimersByTimeAsync(9_999)
      expect(sel.error.value).toBeNull()
      await vi.advanceTimersByTimeAsync(1)

      expect(sel.error.value).toContain('wczytać')

      sel.toggle('museums')
      expect(fetchMock).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a hung /refresh behind a 401 on GET /settings settles the caller: session dropped, the next toggle writes again', async () => {
    vi.useFakeTimers()
    try {
      // Hangs whether or not it was given a signal, so an unbounded refresh really stays unsettled.
      const refreshSignals: (AbortSignal | null | undefined)[] = []
      const fetchMock = vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/auth/refresh') {
          refreshSignals.push(init?.signal)
          return new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => reject(init.signal!.reason))
          })
        }
        return Promise.resolve(res(401, {}))
      })
      const { sel, authStore } = await boot(fetchMock)
      expect(refreshSignals).toHaveLength(1)

      await vi.advanceTimersByTimeAsync(10_000)
      await flushPromises()

      expect(authStore.useAuthStore().isAuthenticated.value).toBe(false)
      expect(sel.selected.value).toEqual(['sacred'])

      sel.toggle('museums')
      expect(sel.selected.value).toEqual(['sacred', 'museums'])
      // The refresh leg (not the already-returned /settings leg) is what had to be bounded.
      expect(refreshSignals[0]?.aborted).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a hung /refresh behind a 401 on PUT /settings settles the save: no permanent single-flight wedge', async () => {
    vi.useFakeTimers()
    try {
      const fetchMock = vi.fn((url: string, init?: RequestInit) => {
        if (url === '/api/auth/refresh') {
          return new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener('abort', () => reject(init.signal!.reason))
          })
        }
        if (init?.method === 'PUT') return Promise.resolve(res(401, {}))
        return Promise.resolve(res(200, { defaultContentMode: 'AUDIO', selectedCategories: ['museums'] }))
      })
      const { sel, authStore } = await boot(fetchMock)
      expect(sel.selected.value).toEqual(['museums'])

      sel.toggle('monuments')
      await flushPromises()

      await vi.advanceTimersByTimeAsync(10_000)
      await flushPromises()

      expect(authStore.useAuthStore().isAuthenticated.value).toBe(false)
      expect(sel.selected.value).toEqual(['sacred'])

      sel.toggle('museums')
      expect(sel.selected.value).toEqual(['sacred', 'museums'])
      expect(fetchMock.mock.calls.filter(([, i]) => (i as RequestInit | undefined)?.method === 'PUT')).toHaveLength(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('logging out aborts the in-flight settings GET', async () => {
    const signals: AbortSignal[] = []
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      signals.push(init!.signal!)
      return new Promise<Response>(() => {})
    })
    const { authStore } = await boot(fetchMock)
    expect(signals).toHaveLength(1)
    expect(signals[0].aborted).toBe(false)

    authStore.clearSession()
    await flushPromises()

    expect(signals[0].aborted).toBe(true)
  })

  it('logging out aborts the in-flight settings PUT', async () => {
    const putSignals: AbortSignal[] = []
    const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method !== 'PUT') {
        return Promise.resolve(res(200, { defaultContentMode: 'AUDIO', selectedCategories: ['museums'] }))
      }
      putSignals.push(init.signal!)
      return new Promise<Response>(() => {})
    })
    const { sel, authStore } = await boot(fetchMock)

    sel.toggle('monuments')
    await flushPromises()
    expect(putSignals).toHaveLength(1)
    expect(putSignals[0].aborted).toBe(false)

    authStore.clearSession()
    await flushPromises()

    expect(putSignals[0].aborted).toBe(true)
  })
})
