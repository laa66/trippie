import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import type { Map as MapLibreMap } from 'maplibre-gl'
import type { FeatureCollection, Point } from 'geojson'
import { useNearbyPoiLayer } from '@/composables/useNearbyPoiLayer'
import { POI_LAYER_ID, POI_SOURCE_ID } from '@/lib/poiLayer'
import type { NearbyItem } from '@/lib/nearbyApi'

function poi(id: string, category = 'museums'): NearbyItem {
  return { id, name: id, category, lat: 51, lon: 17, distanceMeters: 1 }
}

/** Fake MapLibre map exposing only the layer-management surface the composable touches. */
function createFakeMap(opts: { styleLoaded: boolean }) {
  let loadListener: (() => void) | null = null
  let hasSource = false
  const source = { setData: vi.fn((_data: FeatureCollection<Point>) => {}) }

  const map = {
    isStyleLoaded: vi.fn(() => opts.styleLoaded),
    once: vi.fn((type: string, cb: () => void) => {
      if (type === 'load') loadListener = cb
    }),
    getSource: vi.fn(() => (hasSource ? source : undefined)),
    addSource: vi.fn((_id: string, _source: { data: FeatureCollection<Point> }) => {
      hasSource = true
    }),
    addLayer: vi.fn((_layer: { id: string }) => {}),
  }

  return {
    map: map as unknown as MapLibreMap,
    spies: map,
    source,
    fireLoad: () => loadListener?.(),
    hasLoadListener: () => loadListener !== null,
  }
}

describe('useNearbyPoiLayer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('defers adding the source/layer until the style is loaded', () => {
    const fake = createFakeMap({ styleLoaded: false })
    useNearbyPoiLayer(fake.map, ref<NearbyItem[]>([]))

    expect(fake.spies.addSource).not.toHaveBeenCalled()
    expect(fake.spies.addLayer).not.toHaveBeenCalled()
    expect(fake.hasLoadListener()).toBe(true)

    fake.fireLoad()

    expect(fake.spies.addSource).toHaveBeenCalledTimes(1)
    expect(fake.spies.addSource.mock.calls[0][0]).toBe(POI_SOURCE_ID)
    expect(fake.spies.addLayer).toHaveBeenCalledTimes(1)
    expect(fake.spies.addLayer.mock.calls[0][0].id).toBe(POI_LAYER_ID)
  })

  it('adds the source/layer immediately when the style is already loaded', () => {
    const fake = createFakeMap({ styleLoaded: true })
    useNearbyPoiLayer(fake.map, ref<NearbyItem[]>([]))

    expect(fake.spies.once).not.toHaveBeenCalled()
    expect(fake.spies.addSource).toHaveBeenCalledTimes(1)
    expect(fake.spies.addLayer).toHaveBeenCalledTimes(1)
  })

  it('renders many POIs as one source + one layer, never per-POI nodes', () => {
    const fake = createFakeMap({ styleLoaded: true })
    const items = ref<NearbyItem[]>(Array.from({ length: 500 }, (_, i) => poi(`p${i}`)))
    useNearbyPoiLayer(fake.map, items)

    expect(fake.spies.addSource).toHaveBeenCalledTimes(1)
    expect(fake.spies.addLayer).toHaveBeenCalledTimes(1)
    const data = fake.spies.addSource.mock.calls[0][1].data
    expect(data.features).toHaveLength(500)
  })

  it('pushes a refetch via setData without re-adding the source or layer', async () => {
    const fake = createFakeMap({ styleLoaded: true })
    const items = ref<NearbyItem[]>([poi('a')])
    useNearbyPoiLayer(fake.map, items)

    items.value = [poi('b'), poi('c')]
    await nextTick()

    expect(fake.source.setData).toHaveBeenCalledTimes(1)
    expect(fake.source.setData.mock.calls[0][0].features).toHaveLength(2)
    expect(fake.spies.addSource).toHaveBeenCalledTimes(1)
    expect(fake.spies.addLayer).toHaveBeenCalledTimes(1)
  })

  it('does not re-add the source if the load event fires twice', () => {
    const fake = createFakeMap({ styleLoaded: false })
    useNearbyPoiLayer(fake.map, ref<NearbyItem[]>([]))

    fake.fireLoad()
    fake.fireLoad()

    expect(fake.spies.addSource).toHaveBeenCalledTimes(1)
    expect(fake.spies.addLayer).toHaveBeenCalledTimes(1)
  })

  it('stop() halts further setData on items changes', async () => {
    const fake = createFakeMap({ styleLoaded: true })
    const items = ref<NearbyItem[]>([poi('a')])
    const layer = useNearbyPoiLayer(fake.map, items)

    layer.stop()
    items.value = [poi('b')]
    await nextTick()

    expect(fake.source.setData).not.toHaveBeenCalled()
  })
})
