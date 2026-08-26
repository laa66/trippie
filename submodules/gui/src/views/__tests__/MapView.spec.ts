import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

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

// jsdom has no ResizeObserver; MapView only uses it to trigger map.resize().
class FakeResizeObserver {
  observe() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', FakeResizeObserver)

import MapView from '@/views/MapView.vue'

describe('MapView', () => {
  it('creates the map with a non-collapsed, correctly-worded attribution control', async () => {
    mount(MapView, {
      global: {
        stubs: { teleport: true },
      },
    })
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(mapConstructorOptions).toHaveLength(1)
    const { attributionControl } = mapConstructorOptions[0] as {
      attributionControl: { compact: boolean; customAttribution: string }
    }
    expect(attributionControl.compact).toBe(false)
    expect(attributionControl.customAttribution).toBe('© OpenMapTiles © OpenStreetMap contributors')
  })
})
