import { describe, expect, it } from 'vitest'
import type { GeoFix } from '@/composables/useGeolocation'
import { haversineMeters, MAX_ACCURACY_METERS, UNLOCK_RADIUS_METERS } from '@/lib/geo'
import type { NearbyItem } from '@/lib/nearbyApi'
import { evaluateUnlockCandidates, type UnlockPolicy } from '@/lib/proximity'

const ORIGIN = { lat: 51.1079, lon: 17.0385 }
/** Metres per degree of latitude on the same sphere haversineMeters uses, so a north-only offset
 *  round-trips back to the metres asked for (to ~0.3 nanometres — see the exclusive-edge test). */
const METERS_PER_DEGREE_LAT = 6_371_000 * (Math.PI / 180)

function fixAt(accuracyMeters: number, at = ORIGIN): GeoFix {
  return { latitude: at.lat, longitude: at.lon, accuracyMeters, timestamp: 1_000 }
}

function poiAt(id: string, metersNorth: number, over: Partial<NearbyItem> = {}): NearbyItem {
  return {
    id,
    name: id,
    category: 'museums',
    lat: ORIGIN.lat + metersNorth / METERS_PER_DEGREE_LAT,
    lon: ORIGIN.lon,
    // Deliberately a lie in most rows: the server measured this from the nearby QUERY CENTRE, not
    // from the fix, so the evaluator must not read it.
    distanceMeters: 0,
    unlocked: false,
    ...over,
  }
}

const NONE: ReadonlySet<string> = new Set()
/** The default policy: the user still wants to walk. */
const REQUIRED: UnlockPolicy = { requireProximity: true }
/** The "don't make me walk" setting, and the only manual way to exercise M3/M4/M5. */
const EXEMPT: UnlockPolicy = { requireProximity: false }

describe('evaluateUnlockCandidates', () => {
  describe('radius', () => {
    it('admits a POI at 49.9 m', () => {
      expect(evaluateUnlockCandidates(fixAt(10), [poiAt('near', 49.9)], NONE, REQUIRED)).toEqual([
        { osmId: 'near', distanceMeters: expect.closeTo(49.9, 6) },
      ])
    })

    it('rejects a POI at 50.1 m', () => {
      expect(evaluateUnlockCandidates(fixAt(10), [poiAt('far', 50.1)], NONE, REQUIRED)).toEqual([])
    })

    it('rejects a POI at 50.0001 m, i.e. the threshold bites below the radius, not above it', () => {
      expect(evaluateUnlockCandidates(fixAt(10), [poiAt('edge', 50.0001)], NONE, REQUIRED)).toEqual([])
    })

    // The EXCLUSIVE edge, pinned on a measured double rather than on a constructed coordinate:
    // ORIGIN + 50 m of latitude round-trips through haversine as 49.99999999973, so asserting on it
    // would pin float noise and a `<=` mutant would survive. This point sits at EXACTLY 50.0 as a
    // double (found by walking ulps at the equator), which is the only input where `<` and `<=`
    // differ. The precondition is asserted, not assumed: should a future engine's sin/asin shift the
    // last bit, this fails loudly here instead of silently testing nothing.
    it('rejects a POI at exactly the radius: the edge is exclusive, matching ST_DWithin', () => {
      const equator = { lat: 0, lon: 0 }
      const exactlyFifty = { lat: 0.0004496608029593653, lon: 0 }
      expect(haversineMeters(equator, exactlyFifty)).toBe(UNLOCK_RADIUS_METERS)

      const fix: GeoFix = { latitude: equator.lat, longitude: equator.lon, accuracyMeters: 10, timestamp: 1 }
      const item: NearbyItem = {
        id: 'on-the-edge',
        name: null,
        category: 'museums',
        lat: exactlyFifty.lat,
        lon: exactlyFifty.lon,
        distanceMeters: 50,
        unlocked: false,
      }

      expect(evaluateUnlockCandidates(fix, [item], NONE, REQUIRED)).toEqual([])
    })

    it('measures from the fix, ignoring the item distanceMeters the server reported', () => {
      const items = [
        poiAt('lies-near', 500, { distanceMeters: 1 }),
        poiAt('lies-far', 10, { distanceMeters: 9_999 }),
      ]

      expect(evaluateUnlockCandidates(fixAt(10), items, NONE, REQUIRED).map((c) => c.osmId)).toEqual(['lies-far'])
    })
  })

  describe('accuracy', () => {
    it.each([99, MAX_ACCURACY_METERS])('admits candidates at accuracy %s m', (accuracy) => {
      expect(evaluateUnlockCandidates(fixAt(accuracy), [poiAt('near', 10)], NONE, REQUIRED).map((c) => c.osmId)).toEqual(['near'])
    })

    it('yields no candidates at accuracy 101 m, however close the POI is', () => {
      expect(evaluateUnlockCandidates(fixAt(101), [poiAt('on-top', 0)], NONE, REQUIRED)).toEqual([])
    })
  })

  it('yields no candidates without a fix, however close the POIs are', () => {
    expect(evaluateUnlockCandidates(null, [poiAt('on-top', 0), poiAt('also-on-top', 1)], NONE, REQUIRED)).toEqual([])
  })

  describe('already unlocked', () => {
    it('skips a POI the server reported as unlocked', () => {
      expect(evaluateUnlockCandidates(fixAt(10), [poiAt('done', 5, { unlocked: true })], NONE, REQUIRED)).toEqual([])
    })

    it('skips a POI the caller passed in alreadyUnlocked', () => {
      const items = [poiAt('done', 5), poiAt('todo', 6)]

      expect(evaluateUnlockCandidates(fixAt(10), items, new Set(['done']), REQUIRED).map((c) => c.osmId)).toEqual(['todo'])
    })
  })

  it('sorts candidates by ascending distance, so a capped caller keeps the nearest', () => {
    const items = [poiAt('c', 30), poiAt('a', 2), poiAt('b', 11)]

    expect(evaluateUnlockCandidates(fixAt(10), items, NONE, REQUIRED).map((c) => c.osmId)).toEqual(['a', 'b', 'c'])
  })

  it('is pure: it returns a fresh array and leaves the input order alone', () => {
    const items = [poiAt('c', 30), poiAt('a', 2)]

    const result = evaluateUnlockCandidates(fixAt(10), items, NONE, REQUIRED)

    expect(result).not.toBe(items)
    expect(items.map((i) => i.id)).toEqual(['c', 'a'])
  })

  it('yields no candidates for an empty POI list', () => {
    expect(evaluateUnlockCandidates(fixAt(10), [], NONE, REQUIRED)).toEqual([])
  })

  // The server requires accuracyMeters finite and > 0 UNCONDITIONALLY — exempt users included — so
  // anything else is a guaranteed 400 on every fix rather than a policy question. It is reachable:
  // CDP's Emulation.setGeolocationOverride and Playwright's page.setGeolocation report accuracy 0
  // by default, which is exactly the path M3-10's manual and e2e verification walks, and
  // useGeolocation accepts 0 as a measured fix because Number.isFinite(0) is true.
  describe('accuracy lower bound: contract, not policy', () => {
    it.each([
      [0, 'the CDP / Playwright override default'],
      [-1, 'negative'],
      [Number.NaN, 'not a number'],
      [Number.POSITIVE_INFINITY, 'unbounded'],
    ])('yields no candidates at accuracy %s (%s), on EITHER policy', (accuracy) => {
      for (const policy of [REQUIRED, EXEMPT]) {
        expect(evaluateUnlockCandidates(fixAt(accuracy), [poiAt('on-top', 0)], NONE, policy)).toEqual([])
      }
    })
  })

  // The user's own "don't make me walk" setting (frozen #2), not a dev flag: without it the toggle
  // is dead from the UI and M3-10's "toggle ON → unlock from the other side of the city" cannot be
  // reached through the app at all. `false` exempts BOTH distance gates, mirroring the server's
  // `AND (:exempt OR ST_DWithin(...))` and its conditional `accuracy > 100` check.
  describe('exempt from proximity', () => {
    it('admits a POI 5 km away, which the proximity policy refuses', () => {
      const items = [poiAt('across-town', 5_000)]

      expect(evaluateUnlockCandidates(fixAt(10), items, NONE, REQUIRED)).toEqual([])
      expect(evaluateUnlockCandidates(fixAt(10), items, NONE, EXEMPT).map((c) => c.osmId)).toEqual(['across-town'])
    })

    it('admits candidates at accuracy 150 m, the DevTools-override case the proximity policy refuses', () => {
      const items = [poiAt('near', 10)]

      expect(evaluateUnlockCandidates(fixAt(150), items, NONE, REQUIRED)).toEqual([])
      expect(evaluateUnlockCandidates(fixAt(150), items, NONE, EXEMPT).map((c) => c.osmId)).toEqual(['near'])
    })

    it('admits a far POI at a coarse accuracy, i.e. both gates at once', () => {
      const candidates = evaluateUnlockCandidates(fixAt(150), [poiAt('across-town', 5_000)], NONE, EXEMPT)

      expect(candidates.map((c) => c.osmId)).toEqual(['across-town'])
    })

    it('still skips POIs already unlocked, by either route', () => {
      const items = [poiAt('server-says-done', 5_000, { unlocked: true }), poiAt('caller-says-done', 6_000)]

      expect(evaluateUnlockCandidates(fixAt(10), items, new Set(['caller-says-done']), EXEMPT)).toEqual([])
    })

    // The exemption is from the DISTANCE, never from having a measured position: the body requires
    // lat/lon/accuracyMeters, so no fix stays no candidates whatever the toggle says. This is the
    // evaluator's half of the fallback trap — the end-to-end half lives in unlockFallbackTrap.spec.
    it('still yields nothing without a fix', () => {
      expect(evaluateUnlockCandidates(null, [poiAt('on-top', 0)], NONE, EXEMPT)).toEqual([])
    })

    it('still sorts by distance from the fix, so the batch cap keeps the nearest', () => {
      const items = [poiAt('c', 9_000), poiAt('a', 300), poiAt('b', 4_000)]

      expect(evaluateUnlockCandidates(fixAt(10), items, NONE, EXEMPT).map((c) => c.osmId)).toEqual(['a', 'b', 'c'])
    })
  })
})
