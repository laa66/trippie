import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, type Ref } from 'vue'

const mapConstructorOptions: unknown[] = []

vi.mock('maplibre-gl', () => {
  class FakeMap {
    constructor(options: unknown) {
      mapConstructorOptions.push(options)
    }
    on() {}
    off() {}
    once() {}
    resize() {}
    flyTo() {}
    remove() {}
    isStyleLoaded() {
      return false
    }
    getSource() {
      return undefined
    }
    addSource() {}
    addLayer() {}
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

// Only the error leg is driven here; the position/fallback behaviour has its own spec. Mocked
// rather than stubbing `navigator.geolocation`, because what is under test is the chrome MapView
// puts on screen when the composable reports a failure, not the composable.
// The handle is filled from inside the factory: `vi.hoisted` runs before the `vue` import is
// initialised, so a ref cannot be created there.
const geo = vi.hoisted(() => ({ error: null as Ref<string | null> | null }))

vi.mock('@/composables/useGeolocation', async () => {
  const vue = await import('vue')
  geo.error = vue.ref<string | null>(null)
  return {
    WROCLAW_FALLBACK: { latitude: 51.1079, longitude: 17.0385 },
    useGeolocation: () => ({
      position: vue.ref({ latitude: 51.1079, longitude: 17.0385 }),
      isFallback: vue.ref(true),
      error: geo.error,
      // Never a fix here: this spec drives the chrome, and a fix is what would start offering
      // unlocks. The unlock wiring has its own spec (MapView.unlock.spec.ts).
      fix: vue.ref(null),
      start: () => {},
      stop: () => {},
    }),
  }
})

import MapView from '@/views/MapView.vue'

const mountMap = () => mount(MapView, { global: { stubs: { teleport: true } } })

beforeEach(() => {
  mapConstructorOptions.length = 0
  geo.error!.value = null
})

/**
 * The exact HTML the tileserver declares for the basemap source (`/data/v3.json` -> the style's
 * `openmaptiles` source). Pinned here because the assertion below is about a RELATION to it, and
 * a relation cannot be checked against a value the test does not hold.
 */
const TILESERVER_ATTRIBUTION =
  '<a href="https://www.openmaptiles.org/" target="_blank">&copy; OpenMapTiles</a> ' +
  '<a href="https://www.openstreetmap.org/copyright" target="_blank">&copy; OpenStreetMap contributors</a>'

describe('MapView', () => {
  /**
   * M1-12: the OSM attribution is a licence condition, so the map must never come up without one.
   * MapLibre's AttributionControl renders our entry AND the source's, dropping either only when
   * it is a SUBSTRING of the other (a raw `String.includes` on the HTML). The old plain-text line
   * was not a substring, so the notice rendered twice over in the browser.
   *
   * Both halves matter and neither covers the other: the first says we still supply an OSM
   * attribution of our own (the safety net if the tileserver ever declares none), the second says
   * it is exactly the shape that collapses into the tileserver's. Dropping the second lets
   * '© OpenStreetMap contributors' (literal sign, not the entity) or a '… (ODbL)' suffix through,
   * and both of those were measured rendering the attribution twice in chromium.
   */
  it('attributes OpenStreetMap in a form that cannot double up', async () => {
    mountMap()
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(mapConstructorOptions).toHaveLength(1)
    const { attributionControl } = mapConstructorOptions[0] as {
      attributionControl: { compact: boolean; customAttribution: string }
    }
    expect(attributionControl.compact).toBe(false)
    expect(attributionControl.customAttribution).toBe('&copy; OpenStreetMap contributors')
    expect(TILESERVER_ATTRIBUTION).toContain(attributionControl.customAttribution)
  })

  // The banner, its announcement and the icon button's name were all deletable without a single
  // test going red — including the whole `v-if` block.
  it('announces a geolocation failure over the map', async () => {
    geo.error!.value = 'Brak zgody na lokalizację — pokazuję centrum Wrocławia.'
    const wrapper = mountMap()
    await nextTick()

    const notice = wrapper.find('[role="status"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toBe('Brak zgody na lokalizację — pokazuję centrum Wrocławia.')
  })

  it('shows no notice while geolocation is fine', async () => {
    const wrapper = mountMap()
    await nextTick()

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  // Icon-only, so the label is the only name it has.
  it('names the category filter button for assistive tech', async () => {
    const wrapper = mountMap()
    await nextTick()

    expect(wrapper.find('.q-page-sticky button').attributes('aria-label')).toBe('Filtr kategorii')
  })
})
