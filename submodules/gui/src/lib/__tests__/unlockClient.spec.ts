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

/**
 * The same position, measured again a second later. A fix is identified by its timestamp — one
 * request per measured position — so "a later fix" has to carry a new stamp. Re-offering the very
 * same fix is what a pan, a category change and the re-read after a grant all do, and that has to
 * stay silent.
 */
function laterFix(secondsLater = 1): GeoFix {
  return { ...FIX, timestamp: FIX.timestamp + secondsLater * 1_000 }
}

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

    const second = client.attemptUnlock(laterFix(), items, REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    settlers[1].ok({ unlocked: ['edge'], refused: [] })
    await second
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  /**
   * MEDIUM-1 from the M3-08 review, and the other half of the test above. Freeing the in-flight
   * slot is what a `refused` POI needs and what a throw must not skip — but on its own it lets a
   * caller that re-offers the SAME fix (which a pan, a category change and the re-read after a
   * grant all do) buy a second request against a position the device never measured again.
   */
  it('sends nothing for a fix it has already sent a request for, whatever the POIs do', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const edge = poiAt('edge', 49)

    const first = client.attemptUnlock(FIX, [edge], REQUIRED)
    settlers[0].ok({ unlocked: [], refused: ['edge'] })
    await first
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // The POI list changed under the fix — a refetch brought a nearer POI, and the refused one is
    // free again — but the position claim is the same one, so there is nothing new to assert.
    await expect(client.attemptUnlock(FIX, [edge, poiAt('fresh', 10)], REQUIRED)).resolves.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // One new measurement, and both go out — nearest first.
    const third = client.attemptUnlock(laterFix(), [edge, poiAt('fresh', 10)], REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(sentBody(fetchMock, 1).osmIds).toEqual(['fresh', 'edge'])
    settlers[1].ok({ unlocked: ['fresh'], refused: ['edge'] })
    await third
  })

  /**
   * The memory records a request SENT, not a fix seen. The first real fix is also what triggers the
   * first nearby query, so it is routinely evaluated against an empty POI list — and it still has
   * to be the fix that unlocks whatever arrives a moment later, or a device that stands still and
   * produces one fix would never unlock anything at all.
   */
  it('still offers a fix that evaluated to no candidates when the POIs arrive', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()

    await expect(client.attemptUnlock(FIX, [], REQUIRED)).resolves.toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()

    const attempt = client.attemptUnlock(FIX, [poiAt('near', 10)], REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    settlers[0].ok({ unlocked: ['near'], refused: [] })
    await attempt
  })

  /**
   * Pins the ORDER of the per-fix bookkeeping: the key is recorded with the in-flight ids, before
   * the await, not after the response lands. The POI here was NOT in the first batch, so `inFlight`
   * cannot hold it back — only the fix key can. That is a real sequence, not a contrived one: a
   * debounced `moveend` or a category change can land a nearby answer carrying a new POI while the
   * first request is still in the air.
   */
  it('sends nothing more for a fix whose request is still in flight, not even for a POI it has never seen', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()

    const first = client.attemptUnlock(FIX, [poiAt('near', 10)], REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // Asserted BEFORE awaiting the second attempt: if the key were recorded only after the response
    // landed, this attempt would put a request in the air and the await below would hang on a
    // promise nothing settles — a timeout instead of a readable count.
    const second = client.attemptUnlock(FIX, [poiAt('near', 10), poiAt('newcomer', 20)], REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await expect(second).resolves.toBeNull()

    settlers[0].ok({ unlocked: ['near'], refused: [] })
    await first

    // The newcomer is not lost, it just rides the next measured position.
    const next = client.attemptUnlock(laterFix(), [poiAt('newcomer', 20)], REQUIRED)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(sentBody(fetchMock, 1).osmIds).toEqual(['newcomer'])
    settlers[1].ok({ unlocked: ['newcomer'], refused: [] })
    await next
  })

  it('frees the slot when the request fails, surfacing the failure to the caller', async () => {
    const { fetchMock, settlers } = pendingFetch()
    const client = createUnlockClient()
    const items = [poiAt('near', 10)]

    const first = client.attemptUnlock(FIX, items, REQUIRED)
    settlers[0].fail(new Error('network down'))
    await expect(first).rejects.toThrow(/network down/)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    const second = client.attemptUnlock(laterFix(), items, REQUIRED)
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
    const second = client.attemptUnlock(laterFix(), items, REQUIRED)
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
