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
   * At most one request per FIX, too: a second call carrying a fix this client has already sent a
   * request for answers `null` without asking again, whatever has changed in `items` meanwhile.
   *
   * @returns the server's answer, or `null` when nothing was sent — either there was nothing to ask
   *          about (an empty batch is never sent: the server 400s on it, and it could not grant
   *          anything) or a request has already gone out for this same fix.
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
 * The third piece of state is what makes that last sentence true rather than merely intended: the
 * timestamp of the fix the last request was sent for. Freeing the in-flight slot is what a `refused`
 * POI needs and what a throw cannot skip, but on its own it means a caller that offers the SAME fix
 * again — because the POIs changed under it, which a grant's re-read and a pan both do — gets a
 * second request against a position the device has not re-measured. Measured in M3-08 review: two
 * POSTs for one fix, and on the exempt path with 120 POIs a cascade of three POSTs and four GETs out
 * of a single fix, which is the path M3-10 verifies by hand. The timestamp is the fix's natural
 * identity: it comes from the browser's own `GeolocationPosition`, so two offers of one measurement
 * cannot differ on it, and the next measurement cannot collide with it.
 *
 * A factory, not module state, so that state cannot leak between specs (or between sessions) — the
 * caller owns the instance's lifetime.
 */
export function createUnlockClient(): UnlockClient {
  const known = new Set<string>()
  const inFlight = new Set<string>()
  let requestedFixTimestamp: number | null = null

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
    // One request per measured position. Checked before the evaluation and not after it, so a fix
    // re-offered with a changed POI list costs nothing at all.
    if (fix.timestamp === requestedFixTimestamp) {
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
    // Recorded with the in-flight ids, before the await: both entry points into an attempt can run
    // before this one settles, and the batch overflow belongs to the NEXT fix either way.
    requestedFixTimestamp = fix.timestamp
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
