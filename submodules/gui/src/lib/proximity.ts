import type { GeoFix } from '@/composables/useGeolocation'
import { haversineMeters, MAX_ACCURACY_METERS, UNLOCK_RADIUS_METERS } from '@/lib/geo'
import type { NearbyItem } from '@/lib/nearbyApi'

/**
 * A POI this fix is close enough to attempt an unlock for, with the distance the CLIENT measured
 * from the fix itself.
 */
export interface UnlockCandidate {
  osmId: string
  distanceMeters: number
}

/**
 * The user's own unlock policy, read from their settings (`requireProximity`), not a dev flag. It
 * is passed per evaluation and never captured anywhere: the toggle can flip mid-session without a
 * remount, and the server reads the same preference at write time — so a stale copy here would
 * either hide a legitimate unlock or send a request the server answers with 422.
 *
 * `false` is the "don't make me walk" product setting (frozen decision #2, and the only way to
 * exercise M3/M4/M5 by hand). It exempts BOTH distance gates, exactly like the server's
 * `AND (:exempt OR ST_DWithin(...))` and its `if (requireProximity && accuracy > 100)`.
 */
export interface UnlockPolicy {
  requireProximity: boolean
}

/**
 * Decides which POIs a single position fix may be offered for unlocking. Pure: no map, no network,
 * no reactivity — it takes plain values and returns a plain array, so the whole proximity rule is
 * testable as a table and nothing about it depends on render order or request timing.
 *
 * `fix` is a {@link GeoFix}, NOT a `GeoPosition`, and that is the structural gate on the fallback
 * trap: the map legitimately centres on WROCLAW_FALLBACK, which sits inside the old-town POI
 * cluster, so a naive distance check against the map position would unlock dozens of POIs on first
 * render. A fallback has no measured accuracy, so it cannot be expressed as a `GeoFix` at all —
 * `useGeolocation` only ever builds one inside a real `watchPosition` success. The type refuses the
 * substitution; the comment just explains it. (The import is type-only, so this module still pulls
 * in nothing reactive.)
 *
 * The distance is recomputed here rather than read off `item.distanceMeters`: the server measured
 * that one from the NEARBY QUERY CENTRE, which is the map centre — possibly the fallback — and not
 * from this fix. Cost is a non-issue (≤500 POI × haversine is tens of microseconds at the ~1 Hz
 * `watchPosition` rate), so there is no spatial index to build here.
 *
 * @param fix last real fix, or `null` when none has arrived yet → no candidates, full stop.
 * @param items POIs in view, as `nearby` returned them (`unlocked` is server truth).
 * @param alreadyUnlocked POIs the caller already knows are unlocked or has in flight.
 * @param policy this user's {@link UnlockPolicy}, as of this call.
 * @returns candidates sorted by ascending distance, so a caller capped by a batch limit keeps the
 *          nearest ones. Still sorted on the exempt path: the cap has to keep the nearest POIs
 *          there too.
 */
export function evaluateUnlockCandidates(
  fix: GeoFix | null,
  items: readonly NearbyItem[],
  alreadyUnlocked: ReadonlySet<string>,
  policy: UnlockPolicy,
): UnlockCandidate[] {
  if (fix === null) {
    return []
  }
  // UNCONDITIONAL, exempt users included, because `accuracyMeters` is a CONTRACT element and not
  // policy: the server requires it finite and > 0 whatever the preference says, so anything else is
  // a guaranteed 400 on every single fix. It is reachable, not theoretical — CDP's
  // Emulation.setGeolocationOverride and Playwright's page.setGeolocation report `accuracy: 0` by
  // default, which is the very path M3-10's manual and e2e verification walks. Same convention as
  // the upper bound: the client skips a pointless round-trip, the server enforces.
  if (!(Number.isFinite(fix.accuracyMeters) && fix.accuracyMeters > 0)) {
    return []
  }
  // Policy, not contract — hence conditional, mirroring UnlockLocationsService's
  // `if (requireProximity && accuracy > MAX_ACCURACY_METERS)`. An exempt grant never looks at the
  // position at all, so there is nothing for this gate to protect there; keeping it would also
  // break manual verification outright, since a DevTools override reports ~150 m.
  if (policy.requireProximity && fix.accuracyMeters > MAX_ACCURACY_METERS) {
    return []
  }

  const from = { lat: fix.latitude, lon: fix.longitude }
  const candidates: UnlockCandidate[] = []
  for (const item of items) {
    if (item.unlocked || alreadyUnlocked.has(item.id)) {
      continue
    }
    const distanceMeters = haversineMeters(from, { lat: item.lat, lon: item.lon })
    if (!policy.requireProximity || distanceMeters < UNLOCK_RADIUS_METERS) {
      candidates.push({ osmId: item.id, distanceMeters })
    }
  }
  return candidates.sort((a, b) => a.distanceMeters - b.distanceMeters)
}
