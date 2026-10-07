import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import type { NearbyMap } from '@/composables/useNearbyPois'

vi.mock('@/lib/settingsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/settingsApi')>()),
  getSettings: vi.fn(),
  putSettings: vi.fn(),
}))
vi.mock('@/lib/nearbyApi', () => ({ fetchNearby: vi.fn() }))

const STORAGE_KEY = 'trippie.selectedCategories'
const ALL = ['public_art', 'monuments', 'heritage', 'sacred', 'museums', 'viewpoints', 'architecture', 'attractions']

function mockLocalStorage(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial))
  const storage = {
    getItem: vi.fn((k: string) => (store.has(k) ? store.get(k)! : null)),
    setItem: vi.fn((k: string, v: string) => void store.set(k, v)),
    removeItem: vi.fn(),
    clear: vi.fn(),
  }
  vi.stubGlobal('localStorage', storage)
  return storage
}

function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function setup(opts: { loggedIn: boolean; stored?: string[]; rawStored?: string; server?: { defaultContentMode: 'TEXT' | 'AUDIO' | 'BOTH'; selectedCategories: string[] } }) {
  vi.resetModules()
  const storage = mockLocalStorage(
    opts.rawStored !== undefined
      ? { [STORAGE_KEY]: opts.rawStored }
      : opts.stored
        ? { [STORAGE_KEY]: JSON.stringify(opts.stored) }
        : {},
  )
  const api = await import('@/lib/settingsApi')
  const store = await import('@/lib/authStore')
  vi.mocked(api.getSettings).mockResolvedValue(
    opts.server ?? { defaultContentMode: 'BOTH', selectedCategories: ['museums', 'monuments'] },
  )
  if (opts.loggedIn) store.setAccessToken('jwt')
  const { useCategorySelection } = await import('@/composables/useCategorySelection')
  await flushPromises()
  return { api, store, storage, sel: useCategorySelection() }
}

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllGlobals())

describe('useCategorySelection (server-backed)', () => {
  it('hydrates selected and content mode from GET /settings when already logged in', async () => {
    const { sel, api } = await setup({
      loggedIn: true,
      server: { defaultContentMode: 'AUDIO', selectedCategories: ['museums'] },
    })
    expect(api.getSettings).toHaveBeenCalledTimes(1)
    expect(sel.selected.value).toEqual(['museums'])
    expect(sel.defaultContentMode.value).toBe('AUDIO')
  })

  it('hydrates when isAuthenticated flips true after a logged-out cold start (boot refresh / login)', async () => {
    const { sel, api, store } = await setup({ loggedIn: false })
    expect(sel.selected.value).toEqual(ALL)
    expect(api.getSettings).not.toHaveBeenCalled()

    store.setAccessToken('jwt-late')
    await flushPromises()

    expect(api.getSettings).toHaveBeenCalledTimes(1)
    expect(sel.selected.value).toEqual(['museums', 'monuments'])
  })

  it('toggle PUTs the full settings body and applies the server response, not the optimistic value', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    vi.mocked(api.putSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })

    sel.toggle('museums')
    await flushPromises()

    expect(api.putSettings).toHaveBeenCalledWith({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] }, expect.anything())
    expect(sel.selected.value).toEqual(['monuments'])
    expect(sel.error.value).toBeNull()
  })

  it('setContentMode PUTs the new mode with the current categories', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    vi.mocked(api.putSettings).mockResolvedValue({ defaultContentMode: 'TEXT', selectedCategories: ['museums', 'monuments'] })

    sel.setContentMode('TEXT')
    await flushPromises()

    expect(api.putSettings).toHaveBeenCalledWith({ defaultContentMode: 'TEXT', selectedCategories: ['museums', 'monuments'] }, expect.anything())
    expect(sel.defaultContentMode.value).toBe('TEXT')
  })

  it('updates local state optimistically, before the PUT settles', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    const d = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.putSettings).mockReturnValue(d.promise)

    sel.toggle('museums')

    expect(sel.selected.value).toEqual(['monuments'])
    d.resolve({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })
    await flushPromises()
  })

  it('never writes localStorage while logged in', async () => {
    const { sel, api, storage } = await setup({ loggedIn: true })
    vi.mocked(api.putSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })

    sel.toggle('museums')
    await flushPromises()

    expect(storage.setItem).not.toHaveBeenCalled()
  })

  it('logged out: toggle writes localStorage and never calls PUT', async () => {
    const { sel, api, storage } = await setup({ loggedIn: false, stored: ['museums'] })

    sel.toggle('monuments')
    await flushPromises()

    expect(JSON.parse(storage.setItem.mock.calls.at(-1)![1])).toEqual(['museums', 'monuments'])
    expect(api.putSettings).not.toHaveBeenCalled()
  })

  it('logged out with corrupt storage defaults to all 8 and does not call GET', async () => {
    const { sel, api } = await setup({ loggedIn: false, rawStored: '{bad' })
    expect(sel.selected.value).toEqual(ALL)
    expect(api.getSettings).not.toHaveBeenCalled()
  })

  it('a failed PUT (400/network) reverts to the last server-confirmed state and surfaces an error', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    vi.mocked(api.putSettings).mockRejectedValue(new Error('boom'))

    sel.toggle('museums')
    expect(sel.selected.value).toEqual(['monuments'])
    await flushPromises()

    expect(sel.selected.value).toEqual(['museums', 'monuments'])
    expect(sel.error.value).toContain('Nie udało się zapisać')
  })

  it('a failed content-mode PUT reverts the mode', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    vi.mocked(api.putSettings).mockRejectedValue(new Error('boom'))

    sel.setContentMode('AUDIO')
    await flushPromises()

    expect(sel.defaultContentMode.value).toBe('BOTH')
    expect(sel.error.value).not.toBeNull()
  })

  it('a stale PUT response does not override a newer toggle (latest wins)', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    const first = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    const second = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.putSettings).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    sel.toggle('museums')
    sel.toggle('monuments')
    second.resolve({ defaultContentMode: 'BOTH', selectedCategories: [] })
    await flushPromises()
    first.resolve({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })
    await flushPromises()

    expect(sel.selected.value).toEqual([])
  })

  it('logout drops server state back to the localStorage fallback and ignores a late hydrate', async () => {
    const { sel, api, store } = await setup({ loggedIn: false, stored: ['sacred'] })
    const d = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.getSettings).mockReturnValue(d.promise)

    store.setAccessToken('jwt')
    await flushPromises()
    store.clearSession()
    await flushPromises()
    d.resolve({ defaultContentMode: 'BOTH', selectedCategories: ['museums'] })
    await flushPromises()

    expect(sel.selected.value).toEqual(['sacred'])
  })

  it('a failed hydration keeps the current selection and surfaces an error', async () => {
    vi.resetModules()
    mockLocalStorage()
    const api = await import('@/lib/settingsApi')
    const store = await import('@/lib/authStore')
    vi.mocked(api.getSettings).mockRejectedValue(new Error('down'))
    store.setAccessToken('jwt')
    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    await flushPromises()
    const sel = useCategorySelection()
    expect(sel.selected.value).toEqual(ALL)
    expect(sel.error.value).toContain('wczytać')
  })

  it('refuses a toggle while the GET is still in flight: no PUT, selection untouched, error set', async () => {
    vi.resetModules()
    mockLocalStorage()
    const api = await import('@/lib/settingsApi')
    const store = await import('@/lib/authStore')
    const d = deferred<{ defaultContentMode: 'AUDIO'; selectedCategories: string[] }>()
    vi.mocked(api.getSettings).mockReturnValue(d.promise)
    store.setAccessToken('jwt')
    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    const sel = useCategorySelection()

    sel.toggle('museums')
    sel.setContentMode('TEXT')
    await flushPromises()

    expect(api.putSettings).not.toHaveBeenCalled()
    expect(sel.selected.value).toEqual(ALL)
    expect(sel.defaultContentMode.value).toBe('BOTH')
    expect(sel.error.value).not.toBeNull()

    d.resolve({ defaultContentMode: 'AUDIO', selectedCategories: ['sacred', 'heritage'] })
    await flushPromises()
    expect(sel.selected.value).toEqual(['sacred', 'heritage'])
    expect(sel.defaultContentMode.value).toBe('AUDIO')
  })

  it('refuses a toggle after a failed GET: the server settings are never overwritten', async () => {
    vi.resetModules()
    mockLocalStorage()
    const api = await import('@/lib/settingsApi')
    const store = await import('@/lib/authStore')
    vi.mocked(api.getSettings).mockRejectedValue(new Error('down'))
    store.setAccessToken('jwt')
    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    await flushPromises()
    const sel = useCategorySelection()

    sel.toggle('museums')
    sel.setContentMode('TEXT')
    await flushPromises()

    expect(api.putSettings).not.toHaveBeenCalled()
    expect(sel.selected.value).toEqual(ALL)
  })

  async function loggedInAfterFailedGet() {
    vi.resetModules()
    const storage = mockLocalStorage()
    const api = await import('@/lib/settingsApi')
    const store = await import('@/lib/authStore')
    vi.mocked(api.getSettings).mockRejectedValueOnce(new Error('502'))
    store.setAccessToken('jwt')
    const { useCategorySelection } = await import('@/composables/useCategorySelection')
    await flushPromises()
    return { api, store, storage, sel: useCategorySelection() }
  }

  it('a refused toggle after a failed GET retries hydration once, sends no PUT, and the next toggle persists', async () => {
    const { api, sel } = await loggedInAfterFailedGet()
    expect(api.getSettings).toHaveBeenCalledTimes(1)
    vi.mocked(api.getSettings).mockResolvedValue({ defaultContentMode: 'AUDIO', selectedCategories: ['museums', 'monuments'] })
    vi.mocked(api.putSettings).mockImplementation(async (s) => s)

    sel.toggle('sacred')
    await flushPromises()

    expect(api.getSettings).toHaveBeenCalledTimes(2)
    expect(api.putSettings).not.toHaveBeenCalled()
    expect(sel.selected.value).toEqual(['museums', 'monuments'])
    expect(sel.defaultContentMode.value).toBe('AUDIO')

    sel.toggle('museums')
    await flushPromises()
    expect(api.putSettings).toHaveBeenCalledWith({ defaultContentMode: 'AUDIO', selectedCategories: ['monuments'] }, expect.anything())
  })

  it('a second refused toggle while the retry is in flight starts no additional GET', async () => {
    const { api, sel } = await loggedInAfterFailedGet()
    const d = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.getSettings).mockReturnValue(d.promise)

    sel.toggle('sacred')
    sel.toggle('museums')
    sel.setContentMode('TEXT')
    await flushPromises()

    expect(api.getSettings).toHaveBeenCalledTimes(2)
    expect(api.putSettings).not.toHaveBeenCalled()
    d.resolve({ defaultContentMode: 'BOTH', selectedCategories: ALL })
    await flushPromises()
  })

  it('a failed retry leaves the fallback intact with the error shown and no PUT', async () => {
    const { api, sel } = await loggedInAfterFailedGet()
    vi.mocked(api.getSettings).mockRejectedValue(new Error('still down'))

    sel.toggle('sacred')
    await flushPromises()

    expect(api.getSettings).toHaveBeenCalledTimes(2)
    expect(api.putSettings).not.toHaveBeenCalled()
    expect(sel.selected.value).toEqual(ALL)
    expect(sel.error.value).not.toBeNull()

    sel.toggle('sacred')
    await flushPromises()
    expect(api.getSettings).toHaveBeenCalledTimes(3)
    expect(api.putSettings).not.toHaveBeenCalled()
  })

  it('logged out: a toggle only writes localStorage and triggers no GET', async () => {
    const { sel, api, storage } = await setup({ loggedIn: false })
    sel.toggle('museums')
    await flushPromises()
    expect(storage.setItem).toHaveBeenCalled()
    expect(api.getSettings).not.toHaveBeenCalled()
    expect(api.putSettings).not.toHaveBeenCalled()
  })

  it('single-flight: two fast toggles make exactly 2 sequential PUTs, the trailing one carrying the final state', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    let inFlight = 0
    let maxInFlight = 0
    const gates: (() => void)[] = []
    vi.mocked(api.putSettings).mockImplementation(async (s) => {
      inFlight++
      maxInFlight = Math.max(maxInFlight, inFlight)
      await new Promise<void>((r) => gates.push(r))
      inFlight--
      return s
    })

    sel.toggle('museums')
    sel.toggle('monuments')
    await flushPromises()
    expect(api.putSettings).toHaveBeenCalledTimes(1)

    gates[0]()
    await flushPromises()
    expect(api.putSettings).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.putSettings).mock.calls[1][0].selectedCategories).toEqual([])

    gates[1]()
    await flushPromises()
    expect(maxInFlight).toBe(1)
    expect(sel.selected.value).toEqual([])
    expect(api.putSettings).toHaveBeenCalledTimes(2)
  })

  it('a stale echo of the first PUT never wins over a trailing change', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    const gates: ((v: { defaultContentMode: 'BOTH'; selectedCategories: string[] }) => void)[] = []
    vi.mocked(api.putSettings).mockImplementation(() => new Promise((r) => gates.push(r)))

    sel.toggle('museums')
    sel.toggle('monuments')
    gates[0]({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })
    await flushPromises()
    expect(sel.selected.value).toEqual([])
    gates[1]({ defaultContentMode: 'BOTH', selectedCategories: [] })
    await flushPromises()
    expect(sel.selected.value).toEqual([])
  })

  it('does not leak the previous account\'s settings to the next user (A out, B in, B GET fails, B toggles)', async () => {
    const { sel, api, store } = await setup({
      loggedIn: true,
      stored: ['viewpoints'],
      server: { defaultContentMode: 'AUDIO', selectedCategories: ['sacred'] },
    })
    expect(sel.selected.value).toEqual(['sacred'])
    store.clearSession()
    await flushPromises()
    vi.mocked(api.getSettings).mockRejectedValue(new Error('down'))
    vi.mocked(api.putSettings).mockRejectedValue(new Error('down'))

    store.setAccessToken('jwt-B')
    await flushPromises()
    sel.toggle('museums')
    await flushPromises()

    expect(sel.selected.value).not.toEqual(['sacred'])
    expect(sel.defaultContentMode.value).toBe('BOTH')
  })

  it('an echo equal to the optimistic value triggers exactly ONE nearby fetch per toggle', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    const { fetchNearby } = await import('@/lib/nearbyApi')
    vi.mocked(fetchNearby).mockResolvedValue({ items: [], truncated: false })
    const { useNearbyPois } = await import('@/composables/useNearbyPois')
    const map: NearbyMap = {
      on: () => {},
      off: () => {},
      getZoom: () => 14,
      getCenter: () => ({ lng: 17, lat: 51 }),
      getBounds: () => ({ getNorthEast: () => ({ lng: 17, lat: 51.01 }) }),
    }
    const pois = useNearbyPois(map)
    await flushPromises()
    vi.mocked(fetchNearby).mockClear()
    vi.mocked(api.putSettings).mockImplementation(async (s) => ({ ...s, selectedCategories: [...s.selectedCategories] }))

    sel.toggle('museums')
    await flushPromises()

    expect(fetchNearby).toHaveBeenCalledTimes(1)
    pois.stop()
  })

  it('M1 acceptance: unchecking a category re-queries nearby without it, before the PUT settles', async () => {
    const { sel, api } = await setup({ loggedIn: true })
    const { fetchNearby } = await import('@/lib/nearbyApi')
    vi.mocked(fetchNearby).mockResolvedValue({ items: [], truncated: false })
    const { useNearbyPois } = await import('@/composables/useNearbyPois')
    const map: NearbyMap = {
      on: () => {},
      off: () => {},
      getZoom: () => 14,
      getCenter: () => ({ lng: 17, lat: 51 }),
      getBounds: () => ({ getNorthEast: () => ({ lng: 17, lat: 51.01 }) }),
    }
    const pois = useNearbyPois(map)
    const pending = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.putSettings).mockReturnValue(pending.promise)

    sel.toggle('museums')
    await flushPromises()

    const last = vi.mocked(fetchNearby).mock.calls.at(-1)![0]
    expect(last.categories).toEqual(['monuments'])
    pois.stop()
    pending.resolve({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })
    await flushPromises()
  })

  it('a late GET of the previous session does not mark the new session hydrated (no PUT of un-hydrated state)', async () => {
    const { sel, api, store } = await setup({ loggedIn: false })
    const a = deferred<{ defaultContentMode: 'AUDIO'; selectedCategories: string[] }>()
    const b = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.getSettings).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    store.setAccessToken('jwt-A')
    await flushPromises()
    store.clearSession()
    await flushPromises()
    store.setAccessToken('jwt-B')
    await flushPromises()

    a.resolve({ defaultContentMode: 'AUDIO', selectedCategories: ['sacred'] })
    await flushPromises()
    sel.toggle('museums')
    await flushPromises()

    expect(api.putSettings).not.toHaveBeenCalled()
    expect(sel.selected.value).toEqual(ALL)
    expect(sel.defaultContentMode.value).toBe('BOTH')
  })

  it('a late GET of the previous session does not clear hydrating of the new session (no duplicate GET)', async () => {
    const { sel, api, store } = await setup({ loggedIn: false })
    const a = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    const b = deferred<{ defaultContentMode: 'BOTH'; selectedCategories: string[] }>()
    vi.mocked(api.getSettings).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    store.setAccessToken('jwt-A')
    await flushPromises()
    store.clearSession()
    await flushPromises()
    store.setAccessToken('jwt-B')
    await flushPromises()

    a.reject(new Error('aborted'))
    await flushPromises()
    sel.toggle('museums')
    await flushPromises()

    expect(api.getSettings).toHaveBeenCalledTimes(2)
    b.resolve({ defaultContentMode: 'BOTH', selectedCategories: ALL })
    await flushPromises()
  })

  it('a late PUT of the previous session does not clear saving of the new session (no concurrent PUTs)', async () => {
    const { sel, api, store } = await setup({ loggedIn: true })
    type S = { defaultContentMode: 'BOTH'; selectedCategories: string[] }
    const a = deferred<S>()
    const b = deferred<S>()
    vi.mocked(api.putSettings).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    sel.toggle('museums')
    store.clearSession()
    await flushPromises()
    store.setAccessToken('jwt-B')
    await flushPromises()
    sel.toggle('monuments')
    expect(api.putSettings).toHaveBeenCalledTimes(2)

    a.resolve({ defaultContentMode: 'BOTH', selectedCategories: ['monuments'] })
    await flushPromises()
    sel.toggle('sacred')
    await flushPromises()

    expect(api.putSettings).toHaveBeenCalledTimes(2)
    b.resolve({ defaultContentMode: 'BOTH', selectedCategories: [] })
    await flushPromises()
  })

  it('treats [a, a] and [a, b] as different selections (server duplicates)', async () => {
    const { sel, api } = await setup({ loggedIn: true, server: { defaultContentMode: 'BOTH', selectedCategories: ['museums', 'museums'] } })
    vi.mocked(api.putSettings).mockResolvedValue({ defaultContentMode: 'TEXT', selectedCategories: ['museums', 'monuments'] })

    sel.setContentMode('TEXT')
    await flushPromises()

    expect(sel.selected.value).toEqual(['museums', 'monuments'])
  })
})
