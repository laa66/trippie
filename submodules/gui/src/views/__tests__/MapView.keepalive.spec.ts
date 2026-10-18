import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h, type Ref } from 'vue'
import type { NearbyItem } from '@/lib/nearbyApi'
import { itemsToFeatureCollection } from '@/lib/poiLayer'

/**
 * MapView is cached by App.vue's <keep-alive :include="['MapView']">, so navigating away no longer
 * destroys it: `onUnmounted` stops running on navigation and every subscription opened in
 * `onMounted` survives. This spec is the leak gate for that trade — it drives the real
 * /map -> /settings -> /map -> /settings -> /map cycle and asserts that nothing opened once gets
 * opened twice, while the GPS watch (the one thing deliberately paused) is released while hidden.
 */

let mapInstances = 0
const moveEndListeners = new Set<() => void>()
// The hit-test listener is the one subscription M3-08 added, so it joins the leak gate: opened in
// onMounted next to the map, released in onUnmounted next to everything else.
const clickListeners = new Set<(event: unknown) => void>()
const flyTo = vi.fn()
const removeMap = vi.fn()
let styleLoadListener: (() => void) | null = null

vi.mock('maplibre-gl', () => {
  class FakeMap {
    constructor() {
      mapInstances++
    }
    on(type: string, listener: () => void) {
      if (type === 'moveend') moveEndListeners.add(listener)
      if (type === 'click') clickListeners.add(listener)
    }
    off(type: string, listener: () => void) {
      if (type === 'moveend') moveEndListeners.delete(listener)
      if (type === 'click') clickListeners.delete(listener)
    }
    once(type: string, listener: () => void) {
      if (type === 'load') styleLoadListener = listener
    }
    resize() {}
    flyTo(...args: unknown[]) {
      flyTo(...args)
    }
    remove() {
      removeMap()
    }
    isStyleLoaded() {
      return false
    }
    getSource() {
      return undefined
    }
    addSource() {}
    addLayer() {}
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

const fetchNearby = vi.fn(async () => ({ items: [] as NearbyItem[], truncated: false }))
vi.mock('@/lib/nearbyApi', () => ({ fetchNearby: () => fetchNearby() }))
// The unlock HTTP boundary; the client, the dedupe memory and the proximity rule are real.
const postUnlock = vi.fn(async () => ({ unlocked: [] as string[], refused: [] as string[] }))
vi.mock('@/lib/unlockApi', async (importActual) => ({
  ...(await importActual<typeof import('@/lib/unlockApi')>()),
  postUnlock: () => postUnlock(),
}))
// Stubbed for one reason: with the real composable, `poiLayer.stop()` has no effect this spec can
// observe (nearby.stop() already aborts the only thing that could still mutate `items`), so the
// teardown instruction could be deleted unnoticed. Here it is asserted at the call site.
// It also hands back the `items` ref it was given, which is the layer's whole render input: the
// composable pushes `itemsToFeatureCollection(items.value)` into the source and nothing else
// (pinned in useNearbyPoiLayer.spec.ts), so running that same function over the captured ref is
// what the map paints.
const { poiLayerStop, layer } = vi.hoisted(() => ({
  poiLayerStop: vi.fn(),
  layer: { items: null as unknown },
}))
vi.mock('@/composables/useNearbyPoiLayer', () => ({
  useNearbyPoiLayer: (_map: unknown, items: unknown) => {
    layer.items = items
    return { stop: poiLayerStop }
  },
}))
vi.mock('@/lib/settingsApi', () => ({ getSettings: vi.fn(() => new Promise(() => {})), putSettings: vi.fn() }))

class FakeResizeObserver {
  observe() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', FakeResizeObserver)

// Active watch ids, so "never more than one GPS watch" is a property of the whole run and not just
// of the final state.
const activeWatches = new Set<number>()
let maxConcurrentWatches = 0
let nextWatchId = 1
interface StubbedPosition {
  // `accuracy` optional on purpose: a fix without a MEASURED accuracy is not a GeoFix at all, so
  // the specs that leave it out are also asserting that nothing offers an unlock for one.
  coords: { latitude: number; longitude: number; accuracy?: number }
  timestamp?: number
}
let positionCallback: ((pos: StubbedPosition) => void) | null = null

/**
 * A timestamp for one emitted fix. MapView offers an unlock only for a RECENTLY measured fix, and
 * only once per fix, so a stamp has to be near the wall clock and distinct per emission — two fixes
 * sharing a timestamp are one fix as far as the unlock path is concerned, which is the point of
 * MapView.unlock.spec.ts and not of this file.
 */
let fixSequence = 0
function nextFixTimestamp(): number {
  fixSequence += 1
  return Date.now() + fixSequence
}

// Only `geolocation` is swapped: replacing the whole navigator takes userAgent/platform with it
// and leaves the suite depending on import order.
Object.defineProperty(navigator, 'geolocation', {
  configurable: true,
  value: {
    watchPosition(onPosition: (pos: StubbedPosition) => void) {
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

/** Stand-in for any non-cached route (/settings): outside `include`, so it is never kept alive. */
const OtherView = defineComponent({ name: 'OtherView', render: () => h('div', 'settings') })

// Mirrors App.vue: one unconditional KeepAlive whose `include` is scoped to the session.
const Shell = defineComponent({
  props: {
    showMap: { type: Boolean, required: true },
    cached: { type: Array as () => string[], default: () => ['MapView'] },
  },
  setup(props) {
    return () => h(KeepAlive, { include: props.cached }, [props.showMap ? h(MapView) : h(OtherView)])
  },
})

beforeEach(() => {
  mapInstances = 0
  moveEndListeners.clear()
  clickListeners.clear()
  activeWatches.clear()
  maxConcurrentWatches = 0
  styleLoadListener = null
  positionCallback = null
  vi.clearAllMocks()
})

describe('MapView under KeepAlive', () => {
  it('survives /map -> /settings -> /map twice over without duplicating anything', async () => {
    const wrapper = mount(Shell, { props: { showMap: true } })

    const navigate = async (toMap: boolean) => {
      await wrapper.setProps({ showMap: toMap })
      await flushPromises()
    }
    await flushPromises()

    expect(mapInstances).toBe(1)
    expect(activeWatches.size).toBe(1)

    for (const _cycle of [1, 2]) {
      await navigate(false)
      // Deactivated: the map stays, the GPS watch does not.
      expect(removeMap).not.toHaveBeenCalled()
      expect(activeWatches.size).toBe(0)

      await navigate(true)
      expect(activeWatches.size).toBe(1)
    }

    // Nothing opened in onMounted was opened a second time.
    expect(mapInstances).toBe(1)
    expect(moveEndListeners.size).toBe(1)
    expect(clickListeners.size).toBe(1)
    expect(maxConcurrentWatches).toBe(1)
    expect(removeMap).not.toHaveBeenCalled()

    // One position fix must still produce exactly one reaction — a duplicated `watch(position)`
    // (or a second MapView instance behind the cache) would show up as repeated flyTo calls.
    styleLoadListener?.()
    positionCallback?.({ coords: { latitude: 51.11, longitude: 17.04 } })
    await flushPromises()
    expect(flyTo).toHaveBeenCalledTimes(1)
    expect(fetchNearby).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    // The cache is gone, so the single full teardown finally runs.
    expect(removeMap).toHaveBeenCalledTimes(1)
    expect(moveEndListeners.size).toBe(0)
    expect(clickListeners.size).toBe(0)
    expect(activeWatches.size).toBe(0)
  })

  // The production logout path: App.vue empties `include` while /map is on screen, which only
  // clears the keep-alive flag — the teardown happens on the next navigation, through
  // `onUnmounted` alone (no `deactivated` is queued on that route), so this is the one case that
  // actually holds `onUnmounted` to its job.
  it('tears the map down for good when the session drops the cache', async () => {
    const wrapper = mount(Shell, { props: { showMap: true, cached: ['MapView'] } })
    await flushPromises()
    expect(mapInstances).toBe(1)
    expect(activeWatches.size).toBe(1)

    await wrapper.setProps({ cached: [] })
    await flushPromises()
    await wrapper.setProps({ showMap: false })
    await flushPromises()

    expect(removeMap).toHaveBeenCalledTimes(1)
    expect(activeWatches.size).toBe(0)
    expect(moveEndListeners.size).toBe(0)
    expect(clickListeners.size).toBe(0)
    expect(poiLayerStop).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    expect(removeMap).toHaveBeenCalledTimes(1)
  })

  // The dialog is teleported to <body>, so it outlives the view's own DOM: left open across a
  // deactivation it would float over the next screen (the login form, after a revoked session).
  it('closes an open category dialog when the view is deactivated', async () => {
    const wrapper = mount(Shell, { props: { showMap: true } })
    await flushPromises()

    await wrapper.find('.q-page-sticky .q-btn').trigger('click')
    await flushPromises()
    expect(document.querySelectorAll('.q-dialog')).toHaveLength(1)

    await wrapper.setProps({ showMap: false })
    // QDialog unmounts its portal only after the hide transition, so this polls instead of
    // asserting on the next tick.
    // 5s, not 2s: measured at 1931ms under load against the old 2000ms limit.
    await vi.waitFor(() => expect(document.querySelectorAll('.q-dialog')).toHaveLength(0), {
      timeout: 5000,
    })

    wrapper.unmount()
    document.body.innerHTML = ''
  })

  // Same path, one user later: nothing of the previous session may survive into the new view.
  // A fresh instance also resets `firstFixDone`, so the first fix of the new session queries the
  // backend again instead of leaving the old POIs on screen.
  it('mounts a brand new map for the next session', async () => {
    const wrapper = mount(Shell, { props: { showMap: true, cached: ['MapView'] } })
    await flushPromises()
    styleLoadListener?.()
    positionCallback?.({ coords: { latitude: 51.1079, longitude: 17.0385 } })
    await flushPromises()
    expect(fetchNearby).toHaveBeenCalledTimes(1)

    // session ends
    await wrapper.setProps({ cached: [] })
    await flushPromises()
    await wrapper.setProps({ showMap: false })
    await flushPromises()
    expect(removeMap).toHaveBeenCalledTimes(1)

    // next session
    await wrapper.setProps({ cached: ['MapView'], showMap: true })
    await flushPromises()
    expect(mapInstances).toBe(2)
    expect(activeWatches.size).toBe(1)

    positionCallback?.({ coords: { latitude: 52.2297, longitude: 21.0122 } })
    await flushPromises()
    expect(fetchNearby).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    expect(activeWatches.size).toBe(0)
  })

  /**
   * The consequence of keeping the view alive that is NOT a leak: the unlock client survives the
   * deactivation with its dedupe memory intact, and a row could have been written (or never have
   * existed) on another device meanwhile. So what is painted after a reactivation has to be the
   * `unlocked` field of the latest nearby answer — never `knownUnlocked`, which is only there to
   * stop asking twice.
   *
   * The test makes the two disagree on purpose: the server grants the unlock, then its answer
   * after the reactivation says locked. The map must follow the answer, while the client must
   * still refuse to ask again — which is what proves the paint is not coming from its memory.
   */
  it('paints the lock state from the nearby answer after a reactivation, never from its own memory', async () => {
    const poi: NearbyItem = {
      id: 'node/1',
      name: 'Rynek',
      category: 'museums',
      lat: 51.1079,
      lon: 17.0385,
      distanceMeters: 2,
      unlocked: false,
    }
    const lockedState = async () => ({ items: [poi], truncated: false })
    const unlockedState = async () => ({ items: [{ ...poi, unlocked: true }], truncated: false })
    const painted = () =>
      itemsToFeatureCollection((layer.items as Ref<NearbyItem[]>).value).features.map(
        (f) => f.properties?.unlocked,
      )

    fetchNearby
      .mockImplementationOnce(lockedState)
      .mockImplementationOnce(unlockedState)
      .mockImplementationOnce(lockedState)
    postUnlock.mockImplementationOnce(async () => ({ unlocked: [poi.id], refused: [] }))

    const wrapper = mount(Shell, { props: { showMap: true } })
    await flushPromises()

    // A MEASURED fix, at the POI: the only input the unlock path accepts.
    const atThePoi = () =>
      positionCallback?.({
        coords: { latitude: poi.lat, longitude: poi.lon, accuracy: 7 },
        timestamp: nextFixTimestamp(),
      })

    // The fix queries nearby, and the answer landing is what offers the unlock — the grant is then
    // read back from the server rather than patched into the features in hand.
    atThePoi()
    await flushPromises()
    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(fetchNearby).toHaveBeenCalledTimes(2)
    expect(painted()).toEqual([true])

    // /map -> /settings -> /map. The client (and its memory of this POI) survives; the GPS watch
    // does not — so the reactivation re-read is the only thing that can correct the state, and the
    // server now answers that this POI is locked (a row that was never this user's, or one gone).
    await wrapper.setProps({ showMap: false })
    await flushPromises()
    expect(activeWatches.size).toBe(0)
    await wrapper.setProps({ showMap: true })
    await flushPromises()

    expect(mapInstances).toBe(1)
    expect(fetchNearby).toHaveBeenCalledTimes(3)
    expect(painted()).toEqual([false])

    // ... and the client has NOT forgotten its grant: a further fix sends no second request for
    // the same POI. So the locked paint above came from the answer, not from the memory.
    atThePoi()
    await flushPromises()
    expect(postUnlock).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    expect(clickListeners.size).toBe(0)
  })

  // The two halves of the reactivation re-read, which `onActivated` alone cannot separate: Vue
  // fires it right after the first mount too, and querying there would race the query the first
  // real fix makes. So the mount must not fetch, and one away-and-back must fetch exactly once.
  it('re-reads nearby once when the view comes back, and never on the mount itself', async () => {
    const poi: NearbyItem = {
      id: 'node/2',
      name: 'Daleko',
      category: 'viewpoints',
      lat: 51.2,
      lon: 17.2,
      distanceMeters: 4000,
      unlocked: false,
    }
    // Once-implementations, not a persistent one: nothing in this file may inherit a payload.
    const withThePoi = async () => ({ items: [poi], truncated: false })
    fetchNearby.mockImplementationOnce(withThePoi).mockImplementationOnce(withThePoi)

    const wrapper = mount(Shell, { props: { showMap: true } })
    await flushPromises()
    expect(fetchNearby).not.toHaveBeenCalled()

    // One measured fix, so there are POIs on screen whose state could go stale while hidden. The
    // POI is 10 km off, so nothing is offered for unlock and nothing else can refetch.
    positionCallback?.({
      coords: { latitude: 51.1079, longitude: 17.0385, accuracy: 7 },
      timestamp: nextFixTimestamp(),
    })
    await flushPromises()
    expect(fetchNearby).toHaveBeenCalledTimes(1)
    expect(postUnlock).not.toHaveBeenCalled()

    await wrapper.setProps({ showMap: false })
    await flushPromises()
    expect(fetchNearby).toHaveBeenCalledTimes(1)

    await wrapper.setProps({ showMap: true })
    await flushPromises()
    expect(fetchNearby).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })
})
