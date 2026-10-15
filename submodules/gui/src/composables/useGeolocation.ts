import { ref, type Ref } from 'vue'

export interface GeoPosition {
  latitude: number
  longitude: number
}

/** Map center used before (or instead of) a real fix: Wrocław old town. */
export const WROCLAW_FALLBACK: GeoPosition = { latitude: 51.1079, longitude: 17.0385 }

export interface GeoFix {
  latitude: number
  longitude: number
  accuracyMeters: number
  timestamp: number
}

export interface UseGeolocation {
  /** Current best-known position. Starts at (and falls back to) WROCLAW_FALLBACK. */
  position: Ref<GeoPosition>
  /** True while `position` is the fallback, i.e. no real fix has arrived yet. */
  isFallback: Ref<boolean>
  /** Human-readable reason geolocation is unavailable / denied, else null. */
  error: Ref<string | null>
  /**
   * Last real fix with a measured accuracy, else null. Never set from the fallback
   * and kept across later errors, so proximity logic must read this, not `position`:
   * the fallback sits inside the POI cluster and would unlock them.
   */
  fix: Ref<GeoFix | null>
  start: () => void
  stop: () => void
}

/**
 * Watches the device position. On success it updates `position` and clears the
 * fallback flag; on unsupported/denied it keeps the fallback and records a
 * message. Pure of any map/render concern so it is unit-testable in isolation.
 */
export function useGeolocation(): UseGeolocation {
  const position = ref<GeoPosition>({ ...WROCLAW_FALLBACK })
  const isFallback = ref(true)
  const error = ref<string | null>(null)
  const fix = ref<GeoFix | null>(null)
  let watchId: number | null = null

  const start = (): void => {
    if (watchId !== null) return

    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      error.value = 'Geolokalizacja nie jest wspierana w tej przeglądarce.'
      return
    }

    watchId = navigator.geolocation.watchPosition(
      (pos) => {
        position.value = { latitude: pos.coords.latitude, longitude: pos.coords.longitude }
        isFallback.value = false
        error.value = null
        if (Number.isFinite(pos.coords.accuracy)) {
          fix.value = {
            latitude: pos.coords.latitude,
            longitude: pos.coords.longitude,
            accuracyMeters: pos.coords.accuracy,
            timestamp: pos.timestamp,
          }
        }
      },
      (err) => {
        if (!isFallback.value) {
          error.value = 'Chwilowy problem z lokalizacją.'
        } else if (err.code === err.PERMISSION_DENIED) {
          error.value = 'Brak zgody na lokalizację — pokazuję centrum Wrocławia.'
        } else {
          error.value = 'Nie udało się ustalić lokalizacji — pokazuję centrum Wrocławia.'
        }
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 },
    )
  }

  const stop = (): void => {
    if (watchId !== null && typeof navigator !== 'undefined' && navigator.geolocation) {
      navigator.geolocation.clearWatch(watchId)
    }
    watchId = null
  }

  return { position, isFallback, error, fix, start, stop }
}
