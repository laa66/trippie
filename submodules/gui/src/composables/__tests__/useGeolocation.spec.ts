import { afterEach, describe, expect, it, vi } from 'vitest'
import { useGeolocation, WROCLAW_FALLBACK } from '@/composables/useGeolocation'

type SuccessCb = PositionCallback
type ErrorCb = PositionErrorCallback

function mockGeolocation() {
  const clearWatch = vi.fn()
  let successCb: SuccessCb | null = null
  let errorCb: ErrorCb | null = null

  const watchPosition = vi.fn((onSuccess: SuccessCb, onError?: ErrorCb | null) => {
    successCb = onSuccess
    errorCb = onError ?? null
    return 1
  })

  vi.stubGlobal('navigator', { geolocation: { watchPosition, clearWatch } })

  return {
    watchPosition,
    clearWatch,
    emitSuccess: (latitude: number, longitude: number) =>
      successCb?.({ coords: { latitude, longitude } } as GeolocationPosition),
    emitError: (code: number) =>
      errorCb?.({ code, PERMISSION_DENIED: 1 } as GeolocationPositionError),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useGeolocation', () => {
  it('starts on the Wrocław fallback', () => {
    mockGeolocation()
    const { position, isFallback, error } = useGeolocation()

    expect(position.value).toEqual(WROCLAW_FALLBACK)
    expect(isFallback.value).toBe(true)
    expect(error.value).toBeNull()
  })

  it('updates position and clears the fallback on a successful fix', () => {
    const geo = mockGeolocation()
    const { position, isFallback, error, start } = useGeolocation()

    start()
    geo.emitSuccess(52.2297, 21.0122)

    expect(position.value).toEqual({ latitude: 52.2297, longitude: 21.0122 })
    expect(isFallback.value).toBe(false)
    expect(error.value).toBeNull()
  })

  it('keeps the fallback and reports a message when permission is denied', () => {
    const geo = mockGeolocation()
    const { position, isFallback, error, start } = useGeolocation()

    start()
    geo.emitError(1) // PERMISSION_DENIED

    expect(position.value).toEqual(WROCLAW_FALLBACK)
    expect(isFallback.value).toBe(true)
    expect(error.value).toMatch(/zgod/i)
  })

  it('reports unsupported geolocation without registering a watch', () => {
    vi.stubGlobal('navigator', {})
    const { isFallback, error, start } = useGeolocation()

    start()

    expect(isFallback.value).toBe(true)
    expect(error.value).toMatch(/nie jest wspierana/i)
  })

  it('is idempotent: start() registers a single watch, stop() clears it', () => {
    const geo = mockGeolocation()
    const { start, stop } = useGeolocation()

    start()
    start()
    expect(geo.watchPosition).toHaveBeenCalledTimes(1)

    stop()
    expect(geo.clearWatch).toHaveBeenCalledWith(1)
  })
})
