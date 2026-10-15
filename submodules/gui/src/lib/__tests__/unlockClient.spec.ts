import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GeoFix } from '@/composables/useGeolocation'
import type { NearbyItem } from '@/lib/nearbyApi'
import { MAX_UNLOCK_BATCH, type UnlockRequest, type UnlockResponse } from '@/lib/unlockApi'
import type { UnlockPolicy } from '@/lib/proximity'
import { createUnlockClient } from '@/lib/unlockClient'

const ORIGIN = { lat: 51.1079, lon: 17.0385 }
const METERS_PER_DEGREE_LAT = 6_371_000 * (Math.PI / 180)
const FIX: GeoFix = { latitude: ORIGIN.lat, longitude: ORIGIN.lon, accuracyMeters: 8, timestamp: 1_000 }
const REQUIRED: UnlockPolicy = { requireProximity: true }
const EXEMPT: UnlockPolicy = { requireProximity: false }

function poiAt(id: string, metersNorth: number): NearbyItem {
  return {
    id,
    name: id,
    category: 'museums',
    lat: ORIGIN.lat + metersNorth / METERS_PER_DEGREE_LAT,
    lon: ORIGIN.lon,
    distanceMeters: metersNorth,
    unlocked: false,
  }
}

/** A fetch whose responses are settled by hand, so a request can be left hanging mid-flight. */
function pendingFetch() {
  const settlers: Array<{ ok: (body: UnlockResponse) => void; fail: (reason: unknown) => void }> = []
  const fetchMock = vi.fn(
    (_url: string, _init?: RequestInit) =>
      new Promise<Response>((resolve, reject) => {
        settlers.push({
          ok: (body) => resolve({ ok: true, status: 200, json: async () => body } as unknown as Response),
          fail: reject,
        })
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, settlers }
}

type FetchMock = ReturnType<typeof pendingFetch>['fetchMock']

function sentBody(fetchMock: FetchMock, call = 0): UnlockRequest {
  const init = fetchMock.mock.calls[call][1]
  return JSON.parse(init!.body as string) as UnlockRequest
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('createUnlockClient', () => {
  it('sends one request carrying the fix and the in-range ids', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()

    const attempt = client.attemptUnlock(FIX, [poiAt('near', 10), poiAt('far', 400)], REQUIRED)
    settlers[0].ok({ unlocked: ['near'], refused: [] })

    await expect(attempt).resolves.toEqual({ unlocked: ['near'], refused: [] })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(sentBody(fetchMock)).toEqual({
      lat: FIX.latitude,
      lon: FIX.longitude,
      accuracyMeters: FIX.accuracyMeters,
      osmIds: ['near'],
    })
  })

  // The point of the in-flight set. A jittering fix re-evaluates to the same candidates at ~1 Hz,
  // so without it every wobble is another request for POIs already being asked about.
  it('sends ONE request when the same POI comes round again while the first is still in flight', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const items = [poiAt('near', 10)]

    const first = client.attemptUnlock(FIX, items, REQUIRED)
    const second = client.attemptUnlock(FIX, items, REQUIRED)

    await expect(second).resolves.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    settlers[0].ok({ unlocked: ['near'], refused: [] })
    await first
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('never offers a granted POI again, even while nearby still reports it locked', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const items = [poiAt('near', 10)]

    const first = client.attemptUnlock(FIX, items, REQUIRED)
    settlers[0].ok({ unlocked: ['near'], refused: [] })
    await first

    await expect(client.attemptUnlock(FIX, items, REQUIRED)).resolves.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect([...client.knownUnlocked]).toEqual(['near'])
  })

  // `refused` at the radius edge is a NORMAL outcome (sphere vs spheroid), and the user may simply
  // walk closer. So the slot is freed — but only a LATER fix re-sends it; nothing retries in place.
  it('frees a refused POI so the next fix can offer it again, without retrying in place', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const items = [poiAt('edge', 49)]

    const first = client.attemptUnlock(FIX, items, REQUIRED)
    settlers[0].ok({ unlocked: [], refused: ['edge'] })
    await first

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect([...client.knownUnlocked]).toEqual([])

    const second = client.attemptUnlock(FIX, items, REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    settlers[1].ok({ unlocked: ['edge'], refused: [] })
    await second
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('frees the slot when the request fails, surfacing the failure to the caller', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const items = [poiAt('near', 10)]

    const first = client.attemptUnlock(FIX, items, REQUIRED)
    settlers[0].fail(new Error('network down'))
    await expect(first).rejects.toThrow(/network down/)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const second = client.attemptUnlock(FIX, items, REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    settlers[1].ok({ unlocked: ['near'], refused: [] })
    await second
  })

  it('caps one request at the batch limit, keeping the nearest and leaving the rest to the next fix', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const items = Array.from({ length: 60 }, (_, i) => poiAt(`poi-${i}`, 1 + i * 0.5))

    const first = client.attemptUnlock(FIX, items, REQUIRED)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const sent = sentBody(fetchMock).osmIds
    expect(sent).toHaveLength(MAX_UNLOCK_BATCH)
    expect(sent).toEqual(items.slice(0, MAX_UNLOCK_BATCH).map((i) => i.id))

    settlers[0].ok({ unlocked: sent, refused: [] })
    await first

    // The overflow rides the NEXT fix, not a second request against this same position claim.
    const second = client.attemptUnlock(FIX, items, REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(sentBody(fetchMock, 1).osmIds).toEqual(items.slice(MAX_UNLOCK_BATCH).map((i) => i.id))
    settlers[1].ok({ unlocked: [], refused: [] })
    await second
  })

  it.each([
    ['there is no fix', null, [poiAt('on-top', 0)]],
    ['every POI is out of range', FIX, [poiAt('far', 400)]],
    ['every POI is already unlocked', FIX, [{ ...poiAt('done', 5), unlocked: true }]],
    ['there are no POIs at all', FIX, [] as NearbyItem[]],
  ])('sends nothing when %s', async (_case, fix, items) => {
    const { fetchMock } = pendingFetch()

    await expect(createUnlockClient().attemptUnlock(fix, items, REQUIRED)).resolves.toBeNull()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  describe('policy', () => {
    it('sends a POI 5 km away when the user is exempt from proximity', async () => {
      const { fetchMock, settlers } = pendingFetch()
      const client = createUnlockClient()

      const attempt = client.attemptUnlock(FIX, [poiAt('across-town', 5_000)], EXEMPT)

      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(sentBody(fetchMock)).toEqual({
        lat: FIX.latitude,
        lon: FIX.longitude,
        accuracyMeters: FIX.accuracyMeters,
        osmIds: ['across-town'],
      })

      settlers[0].ok({ unlocked: ['across-town'], refused: [] })
      await attempt
    })

    it('sends a coarse fix as measured when exempt, letting the server be the judge', async () => {
      const { fetchMock, settlers } = pendingFetch()
      const coarse: GeoFix = { ...FIX, accuracyMeters: 150 }

      const attempt = createUnlockClient().attemptUnlock(coarse, [poiAt('near', 10)], EXEMPT)

      expect(sentBody(fetchMock).accuracyMeters).toBe(150)

      settlers[0].ok({ unlocked: ['near'], refused: [] })
      await attempt
    })

    // The reason policy is a per-call argument and not frozen in createUnlockClient: the toggle is
    // a module-level ref that can flip mid-session, with nothing remounting the client.
    it('reads the policy per call, so a mid-session flip takes effect on the same client', async () => {
      const { fetchMock, settlers } = pendingFetch()
      const client = createUnlockClient()
      const items = [poiAt('across-town', 5_000)]

      await expect(client.attemptUnlock(FIX, items, REQUIRED)).resolves.toBeNull()
      expect(fetchMock).not.toHaveBeenCalled()

      const attempt = client.attemptUnlock(FIX, items, EXEMPT)
      expect(fetchMock).toHaveBeenCalledTimes(1)

      settlers[0].ok({ unlocked: ['across-town'], refused: [] })
      await attempt

      // And back: a POI granted while exempt is known-unlocked, so flipping the toggle back on does
      // not re-offer it.
      await expect(client.attemptUnlock(FIX, items, REQUIRED)).resolves.toBeNull()
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })

    // An accuracy the server rejects unconditionally (400), so no policy may let it through: the
    // CDP / Playwright override default of 0 would otherwise 400 on every single fix.
    it.each([
      ['proximity required', REQUIRED],
      ['exempt', EXEMPT],
    ])('sends nothing on an accuracy of 0 when %s', async (_case, policy) => {
      const { fetchMock } = pendingFetch()
      const zeroAccuracy: GeoFix = { ...FIX, accuracyMeters: 0 }

      await expect(
        createUnlockClient().attemptUnlock(zeroAccuracy, [poiAt('on-top', 0)], policy),
      ).resolves.toBeNull()

      expect(fetchMock).not.toHaveBeenCalled()
    })
  })

  // Why a factory and not module state: otherwise one spec's granted POIs silently suppress the
  // next spec's request, and in the app one session's unlocks would outlive its logout.
  it('keeps state per instance: a second client knows nothing of the first', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const items = [poiAt('near', 10)]

    const first = createUnlockClient().attemptUnlock(FIX, items, REQUIRED)
    settlers[0].ok({ unlocked: ['near'], refused: [] })
    await first

    const other = createUnlockClient()
    const second = other.attemptUnlock(FIX, items, REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect([...other.knownUnlocked]).toEqual([])
    settlers[1].ok({ unlocked: ['near'], refused: [] })
    await second
  })
})
