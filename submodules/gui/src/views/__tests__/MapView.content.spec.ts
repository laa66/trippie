import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h } from 'vue'
import type { ContentResponse, ContentResult, GenerateResponse } from '@/lib/contentApi'
import type { NearbyItem } from '@/lib/nearbyApi'
import { POLL_CEILING_MS } from '@/lib/contentPoll'
import { useCategorySelection } from '@/composables/useCategorySelection'

/**
 * The seam between the selection, the sheet and useContentGeneration. The real composable, the
 * real sheet, useGeolocation, useNearbyPois and unlockClient all run; only MapLibre and the HTTP
 * boundaries (`fetchNearby`, `postUnlock`, `getContent`, `postGenerate`) are faked.
 */

let clickListener: ((event: unknown) => void) | null = null
const queryRenderedFeatures = vi.fn()

vi.mock('maplibre-gl', () => {
  class FakeMap {
    private layers = new Set<string>()
    private sourceAdded = false
    on(type: string, listener: (event: unknown) => void) {
      if (type === 'click') clickListener = listener
    }
    off(type: string, listener: (event: unknown) => void) {
      if (type === 'click' && clickListener === listener) clickListener = null
    }
    once(type: string, listener: () => void) {
      if (type === 'load') listener()
    }
    isStyleLoaded() {
      return false
    }
    getSource() {
      return this.sourceAdded ? { setData() {} } : undefined
    }
    addSource() {
      this.sourceAdded = true
    }
    addLayer(layer: { id: string }) {
      this.layers.add(layer.id)
    }
    getLayer(id: string) {
      return this.layers.has(id) ? { id } : undefined
    }
    queryRenderedFeatures(...args: unknown[]) {
      return queryRenderedFeatures(...args) ?? []
    }
    project(coords: [number, number]) {
      return { x: coords[0], y: coords[1] }
    }
    resize() {}
    flyTo() {}
    remove() {}
    getZoom() {
      return 15
    }
    getCenter() {
      return { lng: 17.0385, lat: 51.1079 }
    }
    getBounds() {
      return { getNorthEast: () => ({ lng: 17.05, lat: 51.12 }) }
    }
  }
  class FakeMarker {
    setLngLat() {
      return this
    }
    addTo() {
      return this
    }
    remove() {}
  }
  return { Map: FakeMap, Marker: FakeMarker }
})

const fetchNearby = vi.fn()
vi.mock('@/lib/nearbyApi', () => ({ fetchNearby: (...args: unknown[]) => fetchNearby(...args) }))
const postUnlock = vi.fn()
vi.mock('@/lib/unlockApi', async (importActual) => ({
  ...(await importActual<typeof import('@/lib/unlockApi')>()),
  postUnlock: (...args: unknown[]) => postUnlock(...args),
}))
vi.mock('@/lib/settingsApi', () => ({
  getSettings: vi.fn(() => new Promise(() => {})),
  putSettings: vi.fn(),
}))
const getContent = vi.fn<(poiId: string, mode: string, signal?: AbortSignal) => Promise<ContentResult<ContentResponse>>>()
const postGenerate = vi.fn<(poiId: string, mode: string, signal?: AbortSignal) => Promise<ContentResult<GenerateResponse>>>()
vi.mock('@/lib/contentApi', () => ({
  getContent: (...args: Parameters<typeof getContent>) => getContent(...args),
  postGenerate: (...args: Parameters<typeof postGenerate>) => postGenerate(...args),
}))

const activeWatches = new Set<number>()
let maxConcurrentWatches = 0
let nextWatchId = 1
let positionCallback: ((pos: unknown) => void) | null = null
Object.defineProperty(navigator, 'geolocation', {
  configurable: true,
  value: {
    watchPosition(onPosition: (pos: unknown) => void) {
      positionCallback = onPosition
      const id = nextWatchId++
      activeWatches.add(id)
      maxConcurrentWatches = Math.max(maxConcurrentWatches, activeWatches.size)
      return id
    },
    clearWatch(id: number) {
      activeWatches.delete(id)
    },
  },
})

import MapView from '@/views/MapView.vue'
import PoiDetailSheet from '@/components/PoiDetailSheet.vue'

const OPEN: NearbyItem = {
  id: 'node/open',
  name: 'Odblokowane',
  category: 'museums',
  lat: 51.110_06,
  lon: 17.030_05,
  distanceMeters: 7,
  unlocked: true,
}
const OTHER: NearbyItem = { ...OPEN, id: 'node/other', name: 'Inne', lat: 51.110_1 }
const CLOSED: NearbyItem = { ...OPEN, id: 'node/closed', name: 'Zablokowane', lat: 51.2, unlocked: false }

const read = (content: ContentResponse): ContentResult<ContentResponse> => ({ kind: 'content', content })
const PROCESSING = read({ status: 'PROCESSING' })

/** A FRESH array per call, as `fetch` gives: a shared instance would make the items watcher see
 *  no change, and the re-pointing of the selection would quietly leave this file. */
function nearbyReturns(...responses: NearbyItem[][]): void {
  fetchNearby.mockReset()
  let call = 0
  fetchNearby.mockImplementation(async () => {
    const items = responses[Math.min(call, responses.length - 1)] ?? []
    call += 1
    return { items: [...items], truncated: false }
  })
}

async function elapse(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms)
}

/** A real fix, stamped ~1 s after the previous one at the (fake) wall clock, as watchPosition does. */
async function emitFix(): Promise<void> {
  await elapse(1_000)
  positionCallback?.({ coords: { latitude: 51.11, longitude: 17.03, accuracy: 8 }, timestamp: Date.now() })
  await elapse(0)
}

async function tap(item: NearbyItem | null): Promise<void> {
  queryRenderedFeatures.mockReturnValue(
    item === null
      ? []
      : [{ type: 'Feature', geometry: { type: 'Point', coordinates: [100, 200] }, properties: { id: item.id } }],
  )
  clickListener?.({ point: { x: 100, y: 200 } })
  await elapse(0)
}

const mountMap = () => mount(MapView, { global: { stubs: { teleport: true } } })

async function mountWithPois() {
  const wrapper = mountMap()
  await elapse(0)
  await emitFix()
  return wrapper
}

const sheet = (wrapper: { findComponent: ReturnType<typeof mountMap>['findComponent'] }) =>
  wrapper.findComponent(PoiDetailSheet)

const lastSignal = (mock: typeof getContent | typeof postGenerate) => mock.mock.calls[mock.mock.calls.length - 1][2]!

beforeEach(() => {
  vi.useFakeTimers()
  clickListener = null
  positionCallback = null
  activeWatches.clear()
  maxConcurrentWatches = 0
  vi.clearAllMocks()
  queryRenderedFeatures.mockReturnValue([])
  postUnlock.mockResolvedValue({ unlocked: [], refused: [] })
  getContent.mockResolvedValue(read({ status: 'ABSENT' }))
  postGenerate.mockResolvedValue({ kind: 'content', content: { status: 'PROCESSING' } })
  nearbyReturns([OPEN, OTHER, CLOSED])
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('MapView content wiring', () => {
  it('reads once and posts nothing when the sheet opens on an unlocked POI', async () => {
    const wrapper = await mountWithPois()

    await tap(OPEN)
    // Fixes keep arriving with the sheet open, and each nearby refetch re-points the selection at
    // a fresh copy of the same POI.
    for (let i = 0; i < 5; i++) await emitFix()
    const categories = useCategorySelection()
    categories.toggle('sacred')
    await elapse(0)
    categories.toggle('sacred')
    await elapse(3 * POLL_CEILING_MS)

    expect(fetchNearby).toHaveBeenCalledTimes(3)
    expect(getContent).toHaveBeenCalledTimes(1)
    expect(getContent.mock.calls[0].slice(0, 2)).toEqual([OPEN.id, 'TEXT'])
    expect(postGenerate).not.toHaveBeenCalled()
    expect(sheet(wrapper).props('content')).toEqual({ kind: 'absent' })

    wrapper.unmount()
  })

  it('sends nothing for a locked POI and hands the sheet the locked state', async () => {
    const wrapper = await mountWithPois()

    await tap(CLOSED)
    await elapse(POLL_CEILING_MS)

    expect(getContent).not.toHaveBeenCalled()
    expect(postGenerate).not.toHaveBeenCalled()
    expect(sheet(wrapper).props('content')).toEqual({ kind: 'locked' })

    wrapper.unmount()
  })

  it('posts only when the sheet says the user asked for the guide', async () => {
    const wrapper = await mountWithPois()
    await tap(OPEN)
    expect(postGenerate).not.toHaveBeenCalled()

    sheet(wrapper).vm.$emit('generate')
    await elapse(0)

    expect(postGenerate).toHaveBeenCalledTimes(1)
    expect(postGenerate.mock.calls[0].slice(0, 2)).toEqual([OPEN.id, 'TEXT'])
    expect(sheet(wrapper).props('content')).toEqual({ kind: 'processing' })

    wrapper.unmount()
  })

  it('takes the refresh from the sheet: after the ceiling it posts again, and nothing before it', async () => {
    getContent.mockResolvedValueOnce(read({ status: 'ABSENT' })).mockResolvedValue(PROCESSING)
    const wrapper = await mountWithPois()
    await tap(OPEN)
    sheet(wrapper).vm.$emit('generate')
    await elapse(POLL_CEILING_MS)
    expect(sheet(wrapper).props('content')).toEqual({ kind: 'takingLong' })

    const reads = getContent.mock.calls.length
    await elapse(5 * POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(reads)
    expect(postGenerate).toHaveBeenCalledTimes(1)

    sheet(wrapper).vm.$emit('refresh')
    await elapse(0)
    expect(postGenerate).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  it('aborts the request and stops the poll when the sheet closes', async () => {
    getContent.mockResolvedValueOnce(read({ status: 'ABSENT' })).mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithPois()
    await tap(OPEN)
    sheet(wrapper).vm.$emit('generate')
    await elapse(2_000)
    const signal = lastSignal(getContent)
    const reads = getContent.mock.calls.length

    sheet(wrapper).vm.$emit('close')
    await elapse(0)

    expect(signal.aborted).toBe(true)
    await elapse(3 * POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(reads)

    wrapper.unmount()
  })

  it('cancels the poll of the previous POI when another one is tapped', async () => {
    getContent.mockResolvedValueOnce(read({ status: 'ABSENT' })).mockResolvedValue(PROCESSING)
    const wrapper = await mountWithPois()
    await tap(OPEN)
    sheet(wrapper).vm.$emit('generate')
    await elapse(2_000)

    getContent.mockResolvedValue(read({ status: 'ABSENT' }))
    await tap(OTHER)
    const before = getContent.mock.calls.filter((call) => call[0] === OPEN.id).length
    await elapse(3 * POLL_CEILING_MS)

    expect(getContent.mock.calls.filter((call) => call[0] === OPEN.id)).toHaveLength(before)
    expect(getContent.mock.calls.filter((call) => call[0] === OTHER.id)).toHaveLength(1)

    wrapper.unmount()
  })

  it('aborts the request on unmount', async () => {
    getContent.mockReturnValue(new Promise(() => {}))
    const wrapper = await mountWithPois()
    await tap(OPEN)
    const signal = lastSignal(getContent)

    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })

  it('aborts the request and stops the poll when the view is deactivated', async () => {
    const Other = defineComponent({ name: 'OtherView', render: () => h('div') })
    const Shell = defineComponent({
      props: { showMap: { type: Boolean, required: true } },
      setup: (props) => () =>
        h(KeepAlive, { include: ['MapView'] }, [props.showMap ? h(MapView) : h(Other)]),
    })
    getContent.mockResolvedValueOnce(read({ status: 'ABSENT' })).mockReturnValue(new Promise(() => {}))
    const wrapper = mount(Shell, { props: { showMap: true }, global: { stubs: { teleport: true } } })
    await elapse(0)
    await emitFix()
    await tap(OPEN)
    wrapper.findComponent(PoiDetailSheet).vm.$emit('generate')
    await elapse(2_000)
    const signal = lastSignal(getContent)
    const reads = getContent.mock.calls.length

    await wrapper.setProps({ showMap: false })
    await elapse(0)

    expect(signal.aborted).toBe(true)
    await elapse(3 * POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(reads)
    expect(postGenerate).toHaveBeenCalledTimes(1)

    wrapper.unmount()
  })

  // M3-09's measured trap: a sheet that called its own factory opened a second watchPosition.
  it('keeps one GPS watch and leaves every request to the view', async () => {
    const wrapper = await mountWithPois()
    await tap(OPEN)
    sheet(wrapper).vm.$emit('generate')
    await elapse(POLL_CEILING_MS)
    expect(maxConcurrentWatches).toBe(1)
    wrapper.unmount()

    vi.clearAllMocks()
    const watchesOpened = nextWatchId
    const alone = mount(PoiDetailSheet, {
      props: { poi: OPEN, accuracyMeters: 8, distanceFromFixMeters: 7, requireProximity: true, content: { kind: 'absent' } },
      global: { stubs: { teleport: true } },
    })
    await elapse(POLL_CEILING_MS)
    expect(getContent).not.toHaveBeenCalled()
    expect(postGenerate).not.toHaveBeenCalled()
    expect(nextWatchId).toBe(watchesOpened)
    expect(activeWatches.size).toBe(0)
    alone.unmount()
  })
})
