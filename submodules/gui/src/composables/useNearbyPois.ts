import { ref, watch, type Ref } from 'vue'
import { fetchNearby, type NearbyItem } from '@/lib/nearbyApi'
import { haversineMeters, viewportRadiusMeters, MIN_QUERY_ZOOM } from '@/lib/geo'
import { useCategorySelection } from '@/composables/useCategorySelection'

/** moveend debounce window; a burst of pan/zoom events collapses to one query. */
const DEBOUNCE_MS = 400
/** A query only re-fires once the centre drifts this fraction of the current radius. */
const MOVE_FRACTION = 0.3

interface LngLat {
  lng: number
  lat: number
}

/**
 * The slice of a MapLibre `Map` the policy actually uses. Kept minimal so the
 * real map satisfies it and a fake can implement it in tests.
 */
export interface NearbyMap {
  on(type: 'moveend', listener: () => void): void
  off(type: 'moveend', listener: () => void): void
  getZoom(): number
  getCenter(): LngLat
  getBounds(): { getNorthEast(): LngLat }
}

export interface UseNearbyPois {
  items: Ref<NearbyItem[]>
  truncated: Ref<boolean>
  loading: Ref<boolean>
  error: Ref<string | null>
  /** Immediate query (abort-in-flight). Called on the first real geolocation fix. */
  refresh: () => void
  /** Remove the map listener and clear pending timers/requests. */
  stop: () => void
}

interface LatLon {
  lat: number
  lon: number
}

function isAbortError(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError'
}

/**
 * Viewport-driven nearby-POI fetch policy. Queries the spatial backend as the
 * user pans/zooms — debounced on `moveend`, gated by movement and min zoom,
 * abortable — and immediately on category change / first fix. Produces reactive
 * POI state; rendering is a separate concern (M1-11).
 */
export function useNearbyPois(map: NearbyMap): UseNearbyPois {
  const { selected } = useCategorySelection()

  const items = ref<NearbyItem[]>([])
  const truncated = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)

  let controller: AbortController | null = null
  let debounceTimer: ReturnType<typeof setTimeout> | null = null
  let lastQueriedCenter: LatLon | null = null

  function currentCenter(): LatLon {
    const c = map.getCenter()
    return { lat: c.lat, lon: c.lng }
  }

  function currentRadius(center: LatLon): number {
    const ne = map.getBounds().getNorthEast()
    return viewportRadiusMeters(center, { lat: ne.lat, lon: ne.lng })
  }

  async function performFetch(): Promise<void> {
    // Empty-selection trap: an omitted `categories` param is read by the backend
    // as "all 8", so never send an empty selection — clear and bail instead.
    // Empty always clears, regardless of zoom.
    if (selected.value.length === 0) {
      controller?.abort()
      controller = null
      items.value = []
      truncated.value = false
      loading.value = false
      error.value = null
      return
    }

    // Min-zoom guard on every fetch path: too wide a viewport just pauses
    // querying — it keeps the last results rather than clearing them.
    if (map.getZoom() < MIN_QUERY_ZOOM) return

    const center = currentCenter()
    const radius = currentRadius(center)

    controller?.abort()
    const ac = new AbortController()
    controller = ac
    lastQueriedCenter = center
    loading.value = true

    try {
      const res = await fetchNearby({
        lat: center.lat,
        lon: center.lon,
        radius,
        categories: [...selected.value],
        signal: ac.signal,
      })
      if (ac.signal.aborted) return
      items.value = res.items
      truncated.value = res.truncated
      error.value = null
    } catch (err) {
      if (isAbortError(err)) return
      // Keep the last items to avoid flicker; only surface the error flag.
      error.value = 'Nie udało się pobrać punktów w pobliżu.'
    } finally {
      if (controller === ac) {
        loading.value = false
        controller = null
      }
    }
  }

  function onMoveEnd(): void {
    if (debounceTimer !== null) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      debounceTimer = null

      const center = currentCenter()
      const radius = currentRadius(center)
      if (
        lastQueriedCenter !== null &&
        haversineMeters(lastQueriedCenter, center) < MOVE_FRACTION * radius
      ) {
        return
      }

      void performFetch()
    }, DEBOUNCE_MS)
  }

  const stopWatch = watch(selected, () => {
    void performFetch()
  })

  map.on('moveend', onMoveEnd)

  function refresh(): void {
    void performFetch()
  }

  function stop(): void {
    stopWatch()
    map.off('moveend', onMoveEnd)
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
    controller?.abort()
    controller = null
  }

  return { items, truncated, loading, error, refresh, stop }
}
