import { watch, type Ref } from 'vue'
import type { Map as MapLibreMap, GeoJSONSource } from 'maplibre-gl'
import type { NearbyItem } from '@/lib/nearbyApi'
import {
  POI_SOURCE_ID,
  itemsToFeatureCollection,
  nearbyPoiLayer,
} from '@/lib/poiLayer'

export interface UseNearbyPoiLayer {
  /** Stop reacting to `items`; the source/layer are torn down with the map. */
  stop: () => void
}

/**
 * Renders reactive nearby POIs as one GeoJSON source + one category-styled
 * layer (never per-POI DOM markers). The source/layer are added once, after the
 * style is ready; each `items` change is pushed via `setData` on the existing
 * source — the source and layer are never removed and re-added. The user-location
 * DOM marker is a separate concern and sits above this canvas layer.
 */
export function useNearbyPoiLayer(map: MapLibreMap, items: Ref<NearbyItem[]>): UseNearbyPoiLayer {
  function ensureLayer(): void {
    if (map.getSource(POI_SOURCE_ID)) return
    map.addSource(POI_SOURCE_ID, {
      type: 'geojson',
      data: itemsToFeatureCollection(items.value),
    })
    map.addLayer(nearbyPoiLayer())
  }

  function updateData(): void {
    const source = map.getSource(POI_SOURCE_ID) as GeoJSONSource | undefined
    source?.setData(itemsToFeatureCollection(items.value))
  }

  if (map.isStyleLoaded()) {
    ensureLayer()
  } else {
    map.once('load', ensureLayer)
  }

  const stopWatch = watch(items, updateData)

  function stop(): void {
    stopWatch()
  }

  return { stop }
}
