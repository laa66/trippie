import type { GeoFix } from '@/composables/useGeolocation'
import type { NearbyItem } from '@/lib/nearbyApi'
import { evaluateUnlockCandidates, type UnlockPolicy } from '@/lib/proximity'
import { MAX_UNLOCK_BATCH, postUnlock, type UnlockResponse } from '@/lib/unlockApi'

export interface UnlockClient {
  /**
   * POIs the server has confirmed unlocked during this client's life. A DEDUPE memory ("don't ask
   * again"), never a render source: locked/unlocked on the map comes from `nearby`, because the row
   * lives in the database and may have been written on another device.
   */
  readonly knownUnlocked: ReadonlySet<string>
  /**
   * Offers the POIs this fix may unlock, at most one request per call.
   *
   * `policy` is taken PER CALL and deliberately not captured at construction: `requireProximity` is
   * a user setting that can flip mid-session (it lives in a module-level ref in
   * useCategorySelection), so a client built before the flip would keep enforcing the old policy
   * until something happened to remount it.
   *
   * @returns the server's answer, or `null` when there was nothing to ask about — an empty batch is
   *          never sent (the server 400s on it, and it would be a request that cannot grant
   *          anything).
   * @throws whatever {@link postUnlock} throws (AuthApiError, TimeoutError). `refused` ids are NOT
   *         an error — at the radius edge they are the expected outcome, for the reason documented
   *         on `UNLOCK_RADIUS_METERS` in geo.ts.
   */
  attemptUnlock(
    fix: GeoFix | null,
    items: readonly NearbyItem[],
    policy: UnlockPolicy,
    signal?: AbortSignal,
  ): Promise<UnlockResponse | null>
}

/**
 * Builds an unlock client: the unlock rule plus the state that keeps a jittering fix from hammering
 * the endpoint. It holds no policy of its own — see `attemptUnlock`.
 *
 * The expensive thing here is the NETWORK, not the arithmetic. A fix wobbling between three POIs
 * re-evaluates to the same candidates at ~1 Hz, so without this state every wobble would be another
 * request for POIs already being asked about. Two sets hold it: POIs confirmed unlocked (never
 * offered again) and POIs currently in flight (not offered again UNTIL their request settles). A
 * refused POI frees its slot, so it can fly again once the user has walked closer — but only on a
 * later fix, never as a retry in place.
 *
 * A factory, not module state, so that state cannot leak between specs (or between sessions) — the
 * caller owns the instance's lifetime.
 */
export function createUnlockClient(): UnlockClient {
  const known = new Set<string>()
  const inFlight = new Set<string>()

  const attemptUnlock = async (
    fix: GeoFix | null,
    items: readonly NearbyItem[],
    policy: UnlockPolicy,
    signal?: AbortSignal,
  ): Promise<UnlockResponse | null> => {
    // Narrowing, so the request below needs no non-null assertion. evaluateUnlockCandidates is the
    // one that decides this (and also screens the accuracy); this is not a second policy. Note it
    // holds for an EXEMPT user too: the body requires lat/lon/accuracyMeters, so no fix is still no
    // request, whatever the preference says.
    if (fix === null) {
      return null
    }

    const excluded = new Set(known)
    for (const osmId of inFlight) {
      excluded.add(osmId)
    }

    // Nearest first, capped: a cluster can hold more than the server's batch limit, and the
    // overflow belongs to the next fix rather than to a second request against this same position.
    const batch = evaluateUnlockCandidates(fix, items, excluded, policy)
      .slice(0, MAX_UNLOCK_BATCH)
      .map((candidate) => candidate.osmId)
    if (batch.length === 0) {
      return null
    }

    for (const osmId of batch) {
      inFlight.add(osmId)
    }
    try {
      const response = await postUnlock(
        {
          lat: fix.latitude,
          lon: fix.longitude,
          accuracyMeters: fix.accuracyMeters,
          osmIds: batch,
        },
        signal,
      )
      for (const osmId of response.unlocked) {
        known.add(osmId)
      }
      return response
    } finally {
      // Every id sent leaves the in-flight set, including on a throw: the ones that were granted are
      // held out by `known` instead, and the rest must be free to be offered again by a later fix.
      for (const osmId of batch) {
        inFlight.delete(osmId)
      }
    }
  }

  return {
    get knownUnlocked(): ReadonlySet<string> {
      return known
    },
    attemptUnlock,
  }
}
