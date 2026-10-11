import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h } from 'vue'

/**
 * MapView is cached by App.vue's <keep-alive :include="['MapView']">, so navigating away no longer
 * destroys it: `onUnmounted` stops running on navigation and every subscription opened in
 * `onMounted` survives. This spec is the leak gate for that trade — it drives the real
 * /map -> /settings -> /map -> /settings -> /map cycle and asserts that nothing opened once gets
 * opened twice, while the GPS watch (the one thing deliberately paused) is released while hidden.
 */

let mapInstances = 0
const moveEndListeners = new Set<() => void>()
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
    }
    off(type: string, listener: () => void) {
      if (type === 'moveend') moveEndListeners.delete(listener)
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

const fetchNearby = vi.fn(async () => ({ items: [], truncated: false }))
vi.mock('@/lib/nearbyApi', () => ({ fetchNearby: () => fetchNearby() }))
// Stubbed for one reason: with the real composable, `poiLayer.stop()` has no effect this spec can
// observe (nearby.stop() already aborts the only thing that could still mutate `items`), so the
// teardown instruction could be deleted unnoticed. Here it is asserted at the call site.
const { poiLayerStop } = vi.hoisted(() => ({ poiLayerStop: vi.fn() }))
vi.mock('@/composables/useNearbyPoiLayer', () => ({ useNearbyPoiLayer: () => ({ stop: poiLayerStop }) }))
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
let positionCallback: ((pos: { coords: { latitude: number; longitude: number } }) => void) | null = null

// Only `geolocation` is swapped: replacing the whole navigator takes userAgent/platform with it
// and leaves the suite depending on import order.
Object.defineProperty(navigator, 'geolocation', {
  configurable: true,
  value: {
    watchPosition(onPosition: (pos: { coords: { latitude: number; longitude: number } }) => void) {
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
})
