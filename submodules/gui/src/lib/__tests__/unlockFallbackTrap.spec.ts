import { afterEach, describe, expect, it, vi } from 'vitest'
import { useGeolocation, WROCLAW_FALLBACK } from '@/composables/useGeolocation'
import type { NearbyItem } from '@/lib/nearbyApi'
import type { UnlockPolicy } from '@/lib/proximity'
import { createUnlockClient } from '@/lib/unlockClient'

const REQUIRED: UnlockPolicy = { requireProximity: true }
const EXEMPT: UnlockPolicy = { requireProximity: false }
/** Both policies, because no toggle may open a way round the trap. */
const EITHER_POLICY: Array<[string, UnlockPolicy]> = [
  ['proximity required', REQUIRED],
  ['exempt from proximity', EXEMPT],
]

/**
 * THE FALLBACK TRAP. Read this before changing anything in this file.
 *
 * The map starts on WROCLAW_FALLBACK (51.1079, 17.0385) — the middle of the old-town POI cluster.
 * A naive proximity check against the map position therefore unlocks dozens of POIs on the FIRST
 * RENDER, with the user anywhere on earth and no fix ever obtained. Those unlocks are permanent
 * server rows that M4 spends money generating content for, and there is no re-lock.
 *
 * So this spec drives the REAL useGeolocation over a stubbed navigator.geolocation whose success
 * callback is never invoked — exactly the "permission prompt ignored" / "no signal indoors" case —
 * and asserts ZERO unlock requests while `position` sits on the fallback with POIs at 0 m from it.
 * It is deliberately NOT written as `attemptUnlock(null, ...)`: hand-typing the null would assert
 * the evaluator's contract, which proximity.spec.ts already covers, and would keep passing after
 * someone wires `position` where `fix` belongs. The composable has to be in the loop for the
 * regression to be visible, and the type gate at the bottom is what makes the substitution
 * unwriteable rather than merely discouraged.
 *
 * Every assertion runs under BOTH unlock policies. `requireProximity: false` exempts the DISTANCE
 * gates and nothing else, so no fix is still no request — an exemption that also waived the need
 * for a measured position would be this very trap with a settings toggle for a door.
 *
 * Do NOT replace this with a "simpler diff" at review. The anti-vacuity test below is part of it:
 * without it, a zero here could just mean the POI list was unsendable for some other reason.
 */

/** A geolocation that registers the watch and then stays silent forever. */
function silentGeolocation() {
  const watchPosition = vi.fn(() => 1)
  vi.stubGlobal('navigator', { geolocation: { watchPosition, clearWatch: vi.fn() } })
  return { watchPosition }
}

/** A geolocation that registers the watch and lets the spec fire a real success later. */
function emittingGeolocation() {
  let onSuccess: PositionCallback | null = null
  const watchPosition = vi.fn((success: PositionCallback) => {
    onSuccess = success
    return 1
  })
  vi.stubGlobal('navigator', { geolocation: { watchPosition, clearWatch: vi.fn() } })
  return {
    emitFix: (latitude: number, longitude: number, accuracy: number) =>
      onSuccess?.({ coords: { latitude, longitude, accuracy }, timestamp: 4_242 } as GeolocationPosition),
  }
}

/** POIs sitting EXACTLY on the fallback: 0 m away, and locked. The worst possible input. */
function poisOnTheFallback(): NearbyItem[] {
  return ['node/1', 'node/2', 'node/3'].map((id) => ({
    id,
    name: id,
    category: 'museums',
    lat: WROCLAW_FALLBACK.latitude,
    lon: WROCLAW_FALLBACK.longitude,
    distanceMeters: 0,
    unlocked: false,
  }))
}

function okFetch() {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({ unlocked: [], refused: [] }),
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the fallback trap', () => {
  it.each(EITHER_POLICY)(
    'sends ZERO unlock requests while the position is the fallback and POIs sit 0 m from it (%s)',
    async (_case, policy) => {
      const geo = silentGeolocation()
      const fetchMock = okFetch()
      const { position, isFallback, fix, start } = useGeolocation()
      const client = createUnlockClient()

      start()

      expect(geo.watchPosition).toHaveBeenCalledTimes(1)
      expect(isFallback.value).toBe(true)
      expect(position.value).toEqual(WROCLAW_FALLBACK)
      expect(fix.value).toBeNull()

      // Several cycles, as a repeatedly-rendering map would: the answer must be zero every time.
      for (let cycle = 0; cycle < 3; cycle += 1) {
        await expect(client.attemptUnlock(fix.value, poisOnTheFallback(), policy)).resolves.toBeNull()
      }

      expect(fetchMock).not.toHaveBeenCalled()
      expect([...client.knownUnlocked]).toEqual([])
    },
  )

  // Anti-vacuity: the SAME POI list, at the SAME coordinates, does produce exactly one request once
  // a real measured fix exists. So the zero above is caused by the absent fix and nothing else.
  it('sends exactly one request for the same POIs once a real measured fix arrives', async () => {
    const geo = emittingGeolocation()
    const fetchMock = okFetch()
    const { isFallback, fix, start } = useGeolocation()
    const client = createUnlockClient()

    start()
    geo.emitFix(WROCLAW_FALLBACK.latitude, WROCLAW_FALLBACK.longitude, 9)

    expect(isFallback.value).toBe(false)
    expect(fix.value).not.toBeNull()

    await expect(client.attemptUnlock(fix.value, poisOnTheFallback(), REQUIRED)).resolves.toEqual({
      unlocked: [],
      refused: [],
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  // A fix whose accuracy the browser never measured is not a fix: useGeolocation refuses to build
  // one, so this reads as "no fix" rather than as a perfect position at the fallback.
  it.each(EITHER_POLICY)(
    'sends ZERO requests when the success callback carries no measured accuracy (%s)',
    async (_case, policy) => {
      const geo = emittingGeolocation()
      const fetchMock = okFetch()
      const { fix, start } = useGeolocation()

      start()
      geo.emitFix(WROCLAW_FALLBACK.latitude, WROCLAW_FALLBACK.longitude, undefined as unknown as number)

      expect(fix.value).toBeNull()
      await expect(
        createUnlockClient().attemptUnlock(fix.value, poisOnTheFallback(), policy),
      ).resolves.toBeNull()
      expect(fetchMock).not.toHaveBeenCalled()
    },
  )

  /**
   * The structural half of the gate, checked by `vue-tsc`, not by vitest: `position` is a
   * GeoPosition and has no measured accuracy, so it must not be assignable where a GeoFix is
   * required. If anyone widens attemptUnlock (or GeoFix) to accept it, this @ts-expect-error goes
   * unused and the type-check fails — which is the whole point. The call is never executed.
   */
  it('refuses the map position at the type level', () => {
    silentGeolocation()
    const { position } = useGeolocation()
    const client = createUnlockClient()

    const neverRun = (): Promise<unknown> =>
      client.attemptUnlock(
        // @ts-expect-error -- a GeoPosition is not a measured fix, and must never be one
        position.value,
        [],
        REQUIRED,
      )

    expect(neverRun).toBeTypeOf('function')
  })
})
