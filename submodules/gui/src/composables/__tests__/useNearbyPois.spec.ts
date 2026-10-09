import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { viewportRadiusMeters } from '@/lib/geo'
import type { NearbyMap } from '@/composables/useNearbyPois'
import type { FetchNearbyParams, NearbyResponse } from '@/lib/nearbyApi'

vi.mock('@/lib/nearbyApi', () => ({ fetchNearby: vi.fn() }))

const STORAGE_KEY = 'trippie.selectedCategories'
const RESPONSE: NearbyResponse = {
  items: [{ id: 'x', name: 'POI', category: 'museums', lat: 51, lon: 17, distanceMeters: 10 }],
  truncated: false,
}

function mockLocalStorage(initial: Record<string, string> = {}) {
  const store = new Map(Object.entries(initial))
  vi.stubGlobal('localStorage', {
    getItem: vi.fn((k: string) => (store.has(k) ? store.get(k)! : null)),
    setItem: vi.fn((k: string, v: string) => void store.set(k, v)),
    removeItem: vi.fn((k: string) => void store.delete(k)),
    clear: vi.fn(() => store.clear()),
  })
}

interface FakeMap {
  map: NearbyMap
  emitMoveEnd: () => void
  setZoom: (z: number) => void
  setCenter: (lng: number, lat: number) => void
}

/** Fake MapLibre map: NE corner is a fixed offset from the centre, so the derived radius is stable. */
function createFakeMap(opts: {
  zoom: number
  center: { lng: number; lat: number }
  neOffset: { lng: number; lat: number }
}): FakeMap {
  let zoom = opts.zoom
  let center = { ...opts.center }
  let moveEnd: (() => void) | null = null

  const map: NearbyMap = {
    on: (_type, listener) => {
      moveEnd = listener
    },
    off: (_type, listener) => {
      if (moveEnd === listener) moveEnd = null
    },
    getZoom: () => zoom,
    getCenter: () => ({ ...center }),
    getBounds: () => ({
      getNorthEast: () => ({ lng: center.lng + opts.neOffset.lng, lat: center.lat + opts.neOffset.lat }),
    }),
  }

  return {
    map,
    emitMoveEnd: () => moveEnd?.(),
    setZoom: (z) => {
      zoom = z
    },
    setCenter: (lng, lat) => {
      center = { lng, lat }
    },
  }
}

/** A rejection shaped like a browser abort reason: an Error subclass carrying the DOMException name. */
function abortLike(name: string, message: string): Error {
  const err = new Error(message)
  err.name = name
  return err
}

async function bootstrap(opts: {
  selection?: string[]
  zoom?: number
  neOffset?: { lng: number; lat: number }
}) {
  if (opts.selection) {
    mockLocalStorage({ [STORAGE_KEY]: JSON.stringify(opts.selection) })
  } else {
    mockLocalStorage()
  }

  const { fetchNearby } = await import('@/lib/nearbyApi')
  const mockFetch = vi.mocked(fetchNearby)
  mockFetch.mockResolvedValue(RESPONSE)

  const { useNearbyPois } = await import('@/composables/useNearbyPois')
  const { useCategorySelection } = await import('@/composables/useCategorySelection')

  const fake = createFakeMap({
    zoom: opts.zoom ?? 14,
    center: { lng: 17, lat: 51 },
    neOffset: opts.neOffset ?? { lng: 0, lat: 0.01 },
  })
  const api = useNearbyPois(fake.map)

  return { api, fake, mockFetch, useCategorySelection }
}

beforeEach(() => {
  vi.resetModules()
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.clearAllMocks()
})

describe('useNearbyPois', () => {
  it('suppresses the query below MIN_QUERY_ZOOM', async () => {
    const { fake, mockFetch } = await bootstrap({ zoom: 10 })

    fake.emitMoveEnd()
    await vi.advanceTimersByTimeAsync(400)

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('defers a burst of moveend into a single request', async () => {
    // Each move jumps > the 30%-radius threshold, so WITHOUT debounce every
    // moveend would fire its own fetch. Advancing < DEBOUNCE_MS between emits
    // (the timer keeps resetting) proves the deferral, not the movement gate.
    const { fake, mockFetch } = await bootstrap({ zoom: 14 })

    fake.setCenter(17, 51.0)
    fake.emitMoveEnd()
    await vi.advanceTimersByTimeAsync(200)
    fake.setCenter(17, 51.02)
    fake.emitMoveEnd()
    await vi.advanceTimersByTimeAsync(200)
    fake.setCenter(17, 51.04)
    fake.emitMoveEnd()

    // Nothing has fired yet: the window never elapsed.
    expect(mockFetch).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(400)
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('skips a sub-threshold move and fires on a ≥30% move', async () => {
    const { fake, mockFetch } = await bootstrap({ zoom: 14, neOffset: { lng: 0, lat: 0.009 } })

    // First query establishes lastQueriedCenter (radius ≈ 1000 m → threshold ≈ 300 m).
    fake.emitMoveEnd()
    await vi.advanceTimersByTimeAsync(400)
    expect(mockFetch).toHaveBeenCalledTimes(1)

    // ~55 m north — under the threshold, skipped.
    fake.setCenter(17, 51.0005)
    fake.emitMoveEnd()
    await vi.advanceTimersByTimeAsync(400)
    expect(mockFetch).toHaveBeenCalledTimes(1)

    // ~556 m north — over the threshold, fetched.
    fake.setCenter(17, 51.005)
    fake.emitMoveEnd()
    await vi.advanceTimersByTimeAsync(400)
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  it('fires immediately (no debounce) on a category change', async () => {
    const { fake, mockFetch, useCategorySelection } = await bootstrap({
      selection: ['museums', 'monuments'],
      zoom: 14,
    })
    void fake

    useCategorySelection().toggle('museums')
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)

    expect(mockFetch).toHaveBeenCalledTimes(1)
  })

  it('suppresses a category-change fetch below MIN_QUERY_ZOOM (guard on every path)', async () => {
    const { mockFetch, useCategorySelection } = await bootstrap({
      selection: ['museums', 'monuments'],
      zoom: 10,
    })

    useCategorySelection().toggle('museums')
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('cancels a pending debounced query on stop()', async () => {
    const { fake, api, mockFetch } = await bootstrap({ zoom: 14 })

    fake.emitMoveEnd()
    api.stop()
    await vi.advanceTimersByTimeAsync(400)

    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('drops a stale response that resolves after being aborted', async () => {
    const { api, mockFetch } = await bootstrap({ zoom: 14 })

    const oldResponse: NearbyResponse = {
      items: [{ id: 'old', name: null, category: 'museums', lat: 51, lon: 17, distanceMeters: 5 }],
      truncated: false,
    }
    const newResponse: NearbyResponse = {
      items: [{ id: 'new', name: null, category: 'museums', lat: 51, lon: 17, distanceMeters: 9 }],
      truncated: true,
    }

    let resolveOld: (r: NearbyResponse) => void = () => {}
    mockFetch
      .mockReturnValueOnce(new Promise<NearbyResponse>((r) => (resolveOld = r)))
      .mockResolvedValueOnce(newResponse)

    api.refresh() // request 1 — stays pending
    api.refresh() // request 2 — aborts request 1, resolves immediately
    await vi.advanceTimersByTimeAsync(0)
    expect(api.items.value[0].id).toBe('new')

    resolveOld(oldResponse) // late resolution of the aborted request
    await vi.advanceTimersByTimeAsync(0)

    expect(api.items.value[0].id).toBe('new') // stale response ignored
    expect(api.truncated.value).toBe(true)
  })

  it('passes the viewport radius capped at 5000 for a wide viewport', async () => {
    const { mockFetch, api } = await bootstrap({ zoom: 14, neOffset: { lng: 0, lat: 1 } })

    api.refresh()
    await vi.advanceTimersByTimeAsync(0)

    expect((mockFetch.mock.calls[0][0] as FetchNearbyParams).radius).toBe(5000)
  })

  it('passes the true viewport radius for a small viewport', async () => {
    const neOffset = { lng: 0, lat: 0.009 }
    const { mockFetch, api } = await bootstrap({ zoom: 14, neOffset })

    api.refresh()
    await vi.advanceTimersByTimeAsync(0)

    const expected = viewportRadiusMeters({ lat: 51, lon: 17 }, { lat: 51 + neOffset.lat, lon: 17 })
    expect((mockFetch.mock.calls[0][0] as FetchNearbyParams).radius).toBe(expected)
    expect(expected).toBeLessThan(5000)
  })

  it('aborts the in-flight request when a new one starts', async () => {
    const { mockFetch, api } = await bootstrap({ zoom: 14 })

    const signals: AbortSignal[] = []
    mockFetch.mockImplementation((params: FetchNearbyParams) => {
      if (params.signal) signals.push(params.signal)
      return new Promise<NearbyResponse>(() => {}) // never resolves
    })

    api.refresh()
    api.refresh()
    await vi.advanceTimersByTimeAsync(0)

    expect(signals[0].aborted).toBe(true)
  })

  it('does not query on empty selection and clears items', async () => {
    const { api, mockFetch, useCategorySelection } = await bootstrap({
      selection: ['museums'],
      zoom: 14,
    })

    api.refresh()
    await vi.advanceTimersByTimeAsync(0)
    expect(api.items.value.length).toBe(1)
    expect(mockFetch).toHaveBeenCalledTimes(1)

    useCategorySelection().toggle('museums') // → empty selection
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)

    expect(mockFetch).toHaveBeenCalledTimes(1) // no extra request
    expect(api.items.value).toEqual([])
    expect(api.truncated.value).toBe(false)
  })

  // Guards abortLike()'s premise. The day jsdom makes DOMException an Error, this goes red and the
  // workaround gets re-checked instead of quietly becoming unnecessary -- or wrong.
  it('jsdom DOMException is not an Error, which is why abortLike models the browser shape instead', () => {
    expect(new DOMException('x', 'AbortError') instanceof Error).toBe(false)
  })

  // A bounded nearby fetch (the per-attempt budget now lives in apiFetch) makes a TIMEOUT reachable
  // here for the first time. It must not fall into the abort guard, which exists for a query the
  // composable itself superseded: that one is silent on purpose, a timeout has to be visible.
  //
  // The rejection is modelled as an Error carrying the DOMException's name, NOT as a DOMException:
  // jsdom's DOMException is not `instanceof Error` (a real browser's is), so a literal
  // `new DOMException(...)` would make isAbortError() answer false for BOTH names and the assertions
  // would hold no matter what the guard said.
  it('stays silent on a query it cancelled itself but surfaces a timed-out one', async () => {
    const { api, mockFetch } = await bootstrap({ zoom: 14 })
    api.refresh()
    await vi.advanceTimersByTimeAsync(0)
    expect(api.items.value).toEqual(RESPONSE.items)
    expect(api.error.value).toBeNull()

    mockFetch.mockRejectedValueOnce(abortLike('AbortError', 'superseded'))
    api.refresh()
    await vi.advanceTimersByTimeAsync(0)

    expect(api.error.value).toBeNull()

    mockFetch.mockRejectedValueOnce(abortLike('TimeoutError', 'timeout'))
    api.refresh()
    await vi.advanceTimersByTimeAsync(0)

    expect(api.error.value).toBe('Nie udało się pobrać punktów w pobliżu.')
    expect(api.items.value).toEqual(RESPONSE.items) // last results kept, no flicker
    expect(api.loading.value).toBe(false)
  })
})
