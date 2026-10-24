import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { FeatureCollection, Point } from 'geojson'
import type { NearbyItem } from '@/lib/nearbyApi'
import { HIT_TEST_PADDING_PX, POI_LAYER_ID } from '@/lib/poiLayer'
import { useCategorySelection } from '@/composables/useCategorySelection'

/**
 * The seam between the pure proximity rule (proximity.ts), the request-shaping client
 * (unlockClient.ts) and the map. Everything here is about the WIRING, which is where three things
 * can go wrong that neither of those modules can see on its own:
 *
 *  - the unlock policy being frozen at construction instead of read per attempt, so the settings
 *    toggle would need a remount to take effect;
 *  - a permanently failing endpoint turning the ~1 Hz fix stream into a request per fix, because
 *    unlockClient deliberately does not swallow its exceptions;
 *  - the map painting lock state from what this client happens to have unlocked rather than from
 *    what `nearby` says, which is the only source that survives a second device.
 *
 * The real useGeolocation, useNearbyPois, useNearbyPoiLayer, proximity and unlockClient all run;
 * only the two network boundaries (`fetchNearby`, `postUnlock`) and MapLibre are faked, so the
 * assertions below are about production code paths end to end.
 */

let mapInstances = 0
const setData = vi.fn<(data: FeatureCollection<Point>) => void>()
const queryRenderedFeatures = vi.fn()
let clickListener: ((event: unknown) => void) | null = null
let styleLoadListener: (() => void) | null = null

interface RenderedFeature {
  type: 'Feature'
  geometry: { type: 'Point'; coordinates: [number, number] }
  properties: Record<string, unknown>
}

/**
 * `project` is the identity here: a feature's coordinates ARE its screen pixels. The hit-test
 * ranks candidates by pixel distance from the tap point, so making the two spaces the same is
 * what lets the test state the distances it means instead of inverting a Mercator transform.
 */
vi.mock('maplibre-gl', () => {
  class FakeMap {
    private layers = new Set<string>()
    private sourceAdded = false
    constructor() {
      mapInstances++
    }
    on(type: string, listener: (event: unknown) => void) {
      if (type === 'click') clickListener = listener
    }
    off(type: string, listener: (event: unknown) => void) {
      if (type === 'click' && clickListener === listener) clickListener = null
    }
    once(type: string, listener: () => void) {
      if (type === 'load') styleLoadListener = listener
    }
    isStyleLoaded() {
      return false
    }
    getSource() {
      return this.sourceAdded ? { setData } : undefined
    }
    addSource(_id: string, source: { data: FeatureCollection<Point> }) {
      this.sourceAdded = true
      setData(source.data)
    }
    addLayer(layer: { id: string }) {
      this.layers.add(layer.id)
    }
    getLayer(id: string) {
      return this.layers.has(id) ? { id } : undefined
    }
    queryRenderedFeatures(...args: unknown[]) {
      return queryRenderedFeatures(...args) ?? []
    }
    project(coords: [number, number]) {
      return { x: coords[0], y: coords[1] }
    }
    resize() {}
    flyTo() {}
    remove() {}
    getZoom() {
      return 15
    }
    getCenter() {
      return { lng: 17.0385, lat: 51.1079 }
    }
    getBounds() {
      return { getNorthEast: () => ({ lng: 17.05, lat: 51.12 }) }
    }
  }
  class FakeMarker {
    setLngLat() {
      return this
    }
    addTo() {
      return this
    }
    remove() {}
  }
  return { Map: FakeMap, Marker: FakeMarker }
})

const fetchNearby = vi.fn()
vi.mock('@/lib/nearbyApi', () => ({ fetchNearby: (...args: unknown[]) => fetchNearby(...args) }))

// The HTTP boundary, and nothing else: the dedupe, the in-flight bookkeeping, the per-fix memory
// and the batch cap are production code in this spec. So the module is loaded for real and only
// `postUnlock` is replaced — MAX_UNLOCK_BATCH keeps the value the server's own limit is mirrored
// from, which a literal here could silently drift away from.
const postUnlock = vi.fn()
vi.mock('@/lib/unlockApi', async (importActual) => ({
  ...(await importActual<typeof import('@/lib/unlockApi')>()),
  postUnlock: (...args: unknown[]) => postUnlock(...args),
}))
vi.mock('@/lib/settingsApi', () => ({
  getSettings: vi.fn(() => new Promise(() => {})),
  putSettings: vi.fn(),
}))

let positionCallback: ((pos: unknown) => void) | null = null
Object.defineProperty(navigator, 'geolocation', {
  configurable: true,
  value: {
    watchPosition(onPosition: (pos: unknown) => void) {
      positionCallback = onPosition
      return 1
    },
    clearWatch() {},
  },
})

import MapView from '@/views/MapView.vue'
import PoiDetailSheet from '@/components/PoiDetailSheet.vue'
import { MAX_UNLOCK_BATCH } from '@/lib/unlockApi'
import { UNLOCK_RADIUS_METERS } from '@/lib/geo'

/**
 * The clock the view reads. Both time-dependent rules on the unlock path — how old a fix may be
 * when it is offered, and the silence after a streak of failures — are wall-clock comparisons, and
 * nothing here waits on a timer, so time is driven explicitly and promise flushing stays real.
 */
const WATCH_INTERVAL_MS = 1_000
let clock = 0

function advanceClock(ms: number): void {
  clock += ms
}

/**
 * A real measured fix, i.e. the only thing that may ever reach the unlock path.
 *
 * Each call advances the clock by one ~1 Hz `watchPosition` interval and stamps the fix with that
 * moment, because a fix is identified by WHEN it was measured: one unlock request per fix, and only
 * while the fix is fresh. Two fixes fired at the same instant would be one and the same fix.
 */
async function emitFix(lat: number, lon: number, accuracy = 8): Promise<void> {
  advanceClock(WATCH_INTERVAL_MS)
  positionCallback?.({ coords: { latitude: lat, longitude: lon, accuracy }, timestamp: clock })
  await flushPromises()
}

const AT_POI = { lat: 51.11, lon: 17.03 }
/** ~7 m from AT_POI: inside the 50 m radius with room to spare. */
const NEAR: NearbyItem = {
  id: 'node/near',
  name: 'Blisko',
  category: 'museums',
  lat: 51.110_06,
  lon: 17.030_05,
  distanceMeters: 7,
  unlocked: false,
}
/** ~5 km away: outside the radius, so only an exempt user may unlock it. */
const FAR: NearbyItem = {
  id: 'node/far',
  name: 'Daleko',
  category: 'viewpoints',
  lat: 51.155,
  lon: 17.03,
  distanceMeters: 5000,
  unlocked: false,
}

/**
 * Queues one nearby payload per call, the last one answering every later call too, so a spec never
 * depends on how many times the view happened to refetch.
 *
 * Each call hands back a FRESH array, exactly as `fetch` does. Handing out one shared instance
 * would make `items.value = res.items` assign the object the ref already held, so Vue would see no
 * change and the watcher that offers an unlock when POIs ARRIVE would never fire — the second entry
 * point into the attempt, and with it everything that can only go wrong there, would quietly
 * disappear from this file while every assertion still passed.
 */
function nearbyReturns(...responses: NearbyItem[][]): void {
  fetchNearby.mockReset()
  let call = 0
  fetchNearby.mockImplementation(async () => {
    const items = responses[Math.min(call, responses.length - 1)] ?? []
    call += 1
    return { items: [...items], truncated: false }
  })
}

const mountMap = () => mount(MapView, { global: { stubs: { teleport: true } } })

/**
 * The selection the detail sheet (M3-09) will bind to. It is NOT a public API — the sheet renders
 * inside MapView's own template, like CategoryFilter — so this reaches the view's setup state the
 * way test-utils exposes it, instead of the view exporting something no parent has a use for.
 */
function selection(wrapper: ReturnType<typeof mountMap>): NearbyItem | null {
  return (wrapper.vm as unknown as { selectedPoi: NearbyItem | null }).selectedPoi
}

/** The last FeatureCollection the layer pushed into the source: what the map actually paints. */
function paintedFeatures(): RenderedFeature[] {
  const calls = setData.mock.calls
  return (calls[calls.length - 1][0].features ?? []) as unknown as RenderedFeature[]
}

function feature(id: string, screen: [number, number], unlocked: boolean): RenderedFeature {
  return {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: screen },
    properties: { id, name: id, category: 'museums', distanceMeters: 3, unlocked },
  }
}

beforeEach(() => {
  mapInstances = 0
  clickListener = null
  styleLoadListener = null
  positionCallback = null
  vi.clearAllMocks()
  clock = 1_700_000_000_000
  vi.spyOn(Date, 'now').mockImplementation(() => clock)
  queryRenderedFeatures.mockReturnValue([])
  postUnlock.mockResolvedValue({ unlocked: [], refused: [] })
  nearbyReturns([NEAR, FAR])
  // Module-singleton setting: reset so the order of specs in this file cannot change a verdict.
  useCategorySelection().setRequireProximity(true)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('MapView unlock wiring', () => {
  /**
   * The FIRST fix is also what triggers the first nearby query, so when the watcher on `fix` runs
   * there are no POIs yet. The arrival of the answer is therefore the second entry point into the
   * attempt — without it the earliest possible unlock would be the next fix, and a device standing
   * still that produces exactly one fix would never unlock anything.
   *
   * One request, not two: both entry points go through the same function, and the dedupe plus the
   * in-flight set are what make the extra trigger free.
   */
  it('unlocks on the first fix, as soon as the nearby answer lands', async () => {
    const wrapper = mountMap()
    await flushPromises()

    await emitFix(AT_POI.lat, AT_POI.lon)

    expect(fetchNearby).toHaveBeenCalledTimes(1)
    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(postUnlock.mock.calls[0][0]).toEqual({
      lat: AT_POI.lat,
      lon: AT_POI.lon,
      accuracyMeters: 8,
      osmIds: [NEAR.id],
    })

    wrapper.unmount()
  })

  /**
   * The fallback trap from the other side, which the new trigger is the only way to reach: POIs
   * arriving while no fix has ever been measured. The map legitimately sits on WROCLAW_FALLBACK,
   * in the middle of the old-town cluster, so these POIs are 0 m from the map centre — and the
   * answer still has to be zero requests, because there is no GeoFix to offer them with.
   *
   * The query is provoked by a category change, the one path that fetches with no fix at all.
   */
  it('sends nothing when POIs arrive and no fix has ever been measured', async () => {
    const onTheFallback: NearbyItem = {
      id: 'node/fallback',
      name: 'Rynek',
      category: 'museums',
      lat: 51.1079,
      lon: 17.0385,
      distanceMeters: 0,
      unlocked: false,
    }
    nearbyReturns([onTheFallback])

    const wrapper = mountMap()
    await flushPromises()

    const categories = useCategorySelection()
    categories.toggle('sacred')
    await flushPromises()

    expect(fetchNearby).toHaveBeenCalledTimes(1)
    expect(postUnlock).not.toHaveBeenCalled()

    categories.toggle('sacred')
    wrapper.unmount()
  })

  // HIGH-1 from the M3-07 review: the toggle lives in a module-level ref and /settings is a
  // different route, so MapView is NOT remounted when it flips. A policy captured when the client
  // was built would keep the old answer for the rest of the session — hence `mapInstances`, which
  // says the verdict changed inside ONE map instance.
  it('changes behaviour when the proximity toggle flips, with no remount', async () => {
    postUnlock.mockResolvedValueOnce({ unlocked: [NEAR.id], refused: [] })

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)

    // The far POI is 5 km off, so proximity-required means it is never offered.
    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(postUnlock.mock.calls[0][0].osmIds).toEqual([NEAR.id])

    useCategorySelection().setRequireProximity(false)
    await emitFix(AT_POI.lat, AT_POI.lon)

    expect(postUnlock).toHaveBeenCalledTimes(2)
    expect(postUnlock.mock.calls[1][0].osmIds).toEqual([FAR.id])
    expect(mapInstances).toBe(1)

    wrapper.unmount()
  })

  // Frozen: the row lives in the database, outlives the viewport and the category filter, and may
  // have been written by another device — so a grant is read back, never patched into the features
  // in hand.
  it('refetches nearby after a grant and paints the state the server returned', async () => {
    nearbyReturns([NEAR, FAR], [{ ...NEAR, unlocked: true }, FAR])
    postUnlock.mockResolvedValue({ unlocked: [NEAR.id], refused: [] })

    const wrapper = mountMap()
    await flushPromises()
    styleLoadListener?.()
    await emitFix(AT_POI.lat, AT_POI.lon)
    await flushPromises()

    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(fetchNearby).toHaveBeenCalledTimes(2)

    const painted = paintedFeatures()
    expect(painted.map((f) => [f.properties.id, f.properties.unlocked])).toEqual([
      [NEAR.id, true],
      [FAR.id, false],
    ])

    wrapper.unmount()
  })

  // `refused` is the NORMAL answer at the radius edge (the client measures on a sphere, the server
  // on the WGS84 spheroid). It is not a failure, so it must not refetch, must not arm the error
  // cooldown and must not be retried against the same position.
  it('treats a refusal as an answer: no refetch, no retry, no cooldown', async () => {
    postUnlock.mockResolvedValue({ unlocked: [], refused: [NEAR.id] })

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)

    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(fetchNearby).toHaveBeenCalledTimes(1)

    // A later fix may offer it again — the user has had time to walk closer — and that is a fresh
    // request, not a retry of the refused one.
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(2)

    wrapper.unmount()
  })

  /**
   * MEDIUM-3 from the M3-07 review, carried here on purpose: unlockClient's `finally` frees the
   * in-flight slot even on a throw (it MUST, so a refused POI can fly again), so a permanently
   * failing endpoint produced one request per fix — measured at 20 requests for 20 fixes.
   *
   * The cure is a STREAK plus a cooldown, not silence after a single error: one stray 5xx, or one
   * 401 landing while the refresh rotates, would otherwise mute unlocking for ~40 m of walking,
   * which is about the whole window a POI stays in range for. This spec is the shape of that rule —
   * two failures change nothing, the third mutes, and any answer from the server starts the count
   * over.
   */
  it('mutes a failing endpoint only after three failures in a row', async () => {
    postUnlock.mockRejectedValue(new Error('boom'))

    const wrapper = mountMap()
    await flushPromises()

    // Two failures are a blip: the next fix still asks.
    await emitFix(AT_POI.lat, AT_POI.lon)
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(2)

    // An ANSWER — here a refusal, which is the expected one at the radius edge — is a working
    // endpoint, so it clears the count rather than arming anything.
    postUnlock.mockResolvedValueOnce({ unlocked: [], refused: [NEAR.id] })
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(3)
    expect(fetchNearby).toHaveBeenCalledTimes(1)

    // So these two are a fresh streak and still mute nothing...
    await emitFix(AT_POI.lat, AT_POI.lon)
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(5)

    // ... and the third consecutive failure is what buys the silence.
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(6)

    // 20 fixes at the ~1 Hz watchPosition rate, the M3-07 measurement verbatim: no requests.
    for (let i = 0; i < 20; i += 1) {
      await emitFix(AT_POI.lat, AT_POI.lon)
    }
    expect(postUnlock).toHaveBeenCalledTimes(6)

    // Self-healing, which is why the cure is a cooldown and not a give-up threshold: once the
    // silence is over, the next fix asks again — and that is also the only thing that can ever
    // show the endpoint has recovered.
    advanceClock(30_000)
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(7)

    wrapper.unmount()
  })

  /**
   * HIGH-1 from the M3-08 review. The `nearby` answer landing is the second entry point into an
   * attempt, and it fires on a pan, a category change, a reactivation and the refresh after a grant
   * — none of which measures a position. Nothing clears `fix` either: a geolocation error leaves it
   * standing and `stop()` (every time this view is hidden) leaves it standing. So an old fix could
   * be sent as the user's current position long after they walked away from it.
   *
   * Application honesty rather than a security control — the server is never told when the position
   * was measured — but the client must not be the thing claiming it.
   */
  it('offers nothing for a fix measured longer ago than the age bound', async () => {
    // No POIs for the first fix: the POI arrives later, with nothing but the stale fix to offer it
    // against.
    nearbyReturns([], [NEAR])

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(fetchNearby).toHaveBeenCalledTimes(1)
    expect(postUnlock).not.toHaveBeenCalled()

    // Half a minute later, with no new measurement, the category filter refetches and this time
    // the POI standing at the old fix comes back.
    advanceClock(31_000)
    const categories = useCategorySelection()
    categories.toggle('sacred')
    await flushPromises()

    expect(fetchNearby).toHaveBeenCalledTimes(2)
    expect(postUnlock).not.toHaveBeenCalled()

    // Anti-vacuity: the same POI, the same coordinates, a FRESH measurement — one request. So the
    // zero above is the fix's age and nothing else about this setup.
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(postUnlock.mock.calls[0][0].osmIds).toEqual([NEAR.id])

    categories.toggle('sacred')
    wrapper.unmount()
  })

  /**
   * MEDIUM-1 from the M3-08 review. A refused POI frees its in-flight slot — it must, so the user
   * who walks closer can be offered it again — and the answer landing is the second entry point
   * into an attempt, so without a per-fix memory the refusal is retried at once against the very
   * same position. Measured in review: two POSTs out of one fix.
   *
   * What the client's javadoc has promised all along: "only on a later fix, never as a retry in
   * place."
   */
  it('does not retry a refused POI against the same fix, but offers it on the next', async () => {
    postUnlock.mockResolvedValue({ unlocked: [], refused: [NEAR.id] })

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(1)

    // A category change re-answers `nearby` with the same POI, still locked. The POI list changed
    // under the fix; the fix did not, so there is nothing new to claim about the position.
    const categories = useCategorySelection()
    categories.toggle('sacred')
    await flushPromises()
    expect(fetchNearby).toHaveBeenCalledTimes(2)
    expect(postUnlock).toHaveBeenCalledTimes(1)

    // The next measurement is a new position claim, so the refused POI is offered again.
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(2)
    expect(postUnlock.mock.calls[1][0].osmIds).toEqual([NEAR.id])

    categories.toggle('sacred')
    wrapper.unmount()
  })

  /**
   * The same rule where it was measured worst: the EXEMPT path with a cluster bigger than the
   * server's batch limit, which is the path M3-10's manual verification walks. Every POI is a
   * candidate, the grant refetches, the fresh POI list re-enters the attempt, and the overflow goes
   * out against the same position — measured in review as three POSTs and four GETs out of ONE fix.
   *
   * A batch exists to TIGHTEN a grant (one position claim, one radius), so the overflow belongs to
   * the next fix. That is what `MAX_UNLOCK_BATCH`'s own doc says, and this is one fix: one POST.
   */
  it('leaves the batch overflow to the next fix, grant refetch and all', async () => {
    const cluster: NearbyItem[] = Array.from({ length: 120 }, (_, i) => ({ ...NEAR, id: `node/${i}` }))
    nearbyReturns(cluster)
    // The server grants what it is asked for; the payload keeps answering `unlocked: false`, so
    // only the client's own memory holds the granted ones back.
    postUnlock.mockImplementation(async (request: { osmIds: string[] }) => ({
      unlocked: [...request.osmIds],
      refused: [],
    }))
    useCategorySelection().setRequireProximity(false)

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)

    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(postUnlock.mock.calls[0][0].osmIds).toHaveLength(MAX_UNLOCK_BATCH)
    // One query for the fix, one re-read of the grant. The cascade showed four.
    expect(fetchNearby).toHaveBeenCalledTimes(2)

    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(2)
    expect(postUnlock.mock.calls[1][0].osmIds).toEqual(
      cluster.slice(MAX_UNLOCK_BATCH, MAX_UNLOCK_BATCH * 2).map((item) => item.id),
    )

    wrapper.unmount()
  })

  /**
   * LOW-2 from the M3-08 review: teardown aborts the request, but the promise may already have
   * resolved — the continuation then runs with `nearby` nulled by `onUnmounted` and a grant to read
   * back. Dereferencing it there is a programmer error, and the one thing it must not become is a
   * silence that looks like a failing endpoint: with the check in place this is a no-op, without it
   * the run ends in an unhandled TypeError.
   */
  it('survives a grant that lands after the view is gone', async () => {
    // Collected rather than held in a single `let`: the request has to be settled by hand AFTER
    // the unmount, which is the whole window this test is about.
    const settlers: Array<(response: { unlocked: string[]; refused: string[] }) => void> = []
    postUnlock.mockImplementation(
      () =>
        new Promise((resolve) => {
          settlers.push(resolve)
        }),
    )

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(postUnlock).toHaveBeenCalledTimes(1)
    expect(fetchNearby).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    settlers[0]({ unlocked: [NEAR.id], refused: [] })
    await flushPromises()

    // Nothing re-read: there is no view left to paint it.
    expect(fetchNearby).toHaveBeenCalledTimes(1)
  })

  // Teardown aborts the in-flight request; an abort is the view going away, not a failing
  // endpoint, so it must not leave a cooldown behind in a fresh map.
  it('aborts an in-flight unlock on teardown', async () => {
    let signal: AbortSignal | undefined
    postUnlock.mockImplementation((_request: unknown, s?: AbortSignal) => {
      signal = s
      return new Promise(() => {})
    })

    const wrapper = mountMap()
    await flushPromises()
    await emitFix(AT_POI.lat, AT_POI.lon)
    expect(signal?.aborted).toBe(false)

    wrapper.unmount()
    expect(signal?.aborted).toBe(true)
  })
})

describe('MapView POI selection', () => {
  async function mountWithPois() {
    const wrapper = mountMap()
    await flushPromises()
    styleLoadListener?.()
    await emitFix(AT_POI.lat, AT_POI.lon)
    await flushPromises()
    return wrapper
  }

  function tap(x: number, y: number): void {
    clickListener?.({ point: { x, y } })
  }

  // The dot stays 6 px (a 12 px target) and the layer stays single; the frozen 44 px floor is met
  // by querying a box around the tap instead.
  it('hit-tests a 44 px box on the POI layer alone', async () => {
    const wrapper = await mountWithPois()

    tap(100, 200)

    expect(queryRenderedFeatures).toHaveBeenCalledTimes(1)
    const [geometry, options] = queryRenderedFeatures.mock.calls[0]
    expect(HIT_TEST_PADDING_PX).toBe(22)
    expect(geometry).toEqual([
      [78, 178],
      [122, 222],
    ])
    expect(options).toEqual({ layers: [POI_LAYER_ID] })

    wrapper.unmount()
  })

  // Frozen decision #5: a tap that does nothing reads as a broken app, not as a locked POI. The
  // nearest dot wins even when it is the locked one.
  it('selects the nearest POI in the box, locked ones included', async () => {
    const wrapper = await mountWithPois()
    queryRenderedFeatures.mockReturnValue([
      feature(FAR.id, [112, 200], true),
      feature(NEAR.id, [103, 200], false),
    ])

    tap(100, 200)
    await flushPromises()

    // The whole item out of the latest nearby payload, not just the id the feature carried.
    expect(selection(wrapper)).toEqual(NEAR)

    wrapper.unmount()
  })

  it('clears the selection on a tap that hits no POI', async () => {
    const wrapper = await mountWithPois()
    queryRenderedFeatures.mockReturnValue([feature(NEAR.id, [100, 200], false)])
    tap(100, 200)
    await flushPromises()
    expect(selection(wrapper)?.id).toBe(NEAR.id)

    queryRenderedFeatures.mockReturnValue([])
    tap(400, 400)
    await flushPromises()
    expect(selection(wrapper)).toBeNull()

    wrapper.unmount()
  })

  // The sheet (M3-09) renders this object, so a grant that lands while it is open has to reach it.
  // The id is all that is kept across the refetch; the state shown comes from the new payload.
  it('re-points the selection at the server payload after a grant', async () => {
    nearbyReturns([NEAR, FAR], [{ ...NEAR, unlocked: true }, FAR])
    // The first offer is refused (the user is at the radius edge), so the tap below sees a locked
    // POI; the next fix is the one that is granted.
    postUnlock.mockResolvedValueOnce({ unlocked: [], refused: [NEAR.id] })
    postUnlock.mockResolvedValue({ unlocked: [NEAR.id], refused: [] })

    const wrapper = await mountWithPois()
    queryRenderedFeatures.mockReturnValue([feature(NEAR.id, [100, 200], false)])
    tap(100, 200)
    await flushPromises()
    expect(selection(wrapper)?.unlocked).toBe(false)

    await emitFix(AT_POI.lat, AT_POI.lon)
    await flushPromises()

    expect(selection(wrapper)).toEqual({ ...NEAR, unlocked: true })

    wrapper.unmount()
  })

  it('drops a selection the latest nearby answer no longer carries', async () => {
    nearbyReturns([NEAR, FAR], [FAR])

    const wrapper = await mountWithPois()
    queryRenderedFeatures.mockReturnValue([feature(NEAR.id, [100, 200], false)])
    tap(100, 200)
    await flushPromises()
    expect(selection(wrapper)?.id).toBe(NEAR.id)

    // Unticking the selected POI's category refetches, and the second payload no longer has it:
    // there is no dot left on the map for the selection to point at.
    const categories = useCategorySelection()
    categories.toggle('museums')
    await flushPromises()

    expect(selection(wrapper)).toBeNull()

    categories.toggle('museums')
    wrapper.unmount()
  })
})

/**
 * The seam between the gate and what the sheet SAYS. M3-09 put the sheet's inputs in the template
 * and nothing covered them from here, which is how `fix` reached the sheet while `offerUnlock` was
 * already refusing that same fix for being too old. The three specs below are about one rule: the
 * sheet speaks only from a fix the gate would act on.
 */
describe('MapView detail sheet inputs', () => {
  /**
   * Deliberately far past MAX_FIX_AGE_MS instead of a step over it: this file cannot import that
   * constant (it is local to the view, and M3-09 removed `defineExpose`), so pinning the number
   * here would only mirror it badly. What these specs pin is the RULE — a fix this old is not the
   * user's position — which survives a change to the bound itself.
   */
  const LONG_STALE_MS = 10 * 60_000

  async function mountWithPois() {
    const wrapper = mountMap()
    await flushPromises()
    styleLoadListener?.()
    return wrapper
  }

  function tapNear(): void {
    queryRenderedFeatures.mockReturnValue([feature(NEAR.id, [100, 200], false)])
    clickListener?.({ point: { x: 100, y: 200 } })
  }

  function sheetProps(wrapper: ReturnType<typeof mountMap>) {
    return wrapper.findComponent(PoiDetailSheet).props() as unknown as {
      accuracyMeters: number | null
      distanceFromFixMeters: number | null
    }
  }

  it('hands the sheet the accuracy and a distance measured from a fresh fix', async () => {
    const wrapper = await mountWithPois()
    await emitFix(AT_POI.lat, AT_POI.lon, 12)

    tapNear()
    await flushPromises()

    const props = sheetProps(wrapper)
    expect(props.accuracyMeters).toBe(12)
    // NEAR sits ~7 m from the fix; the exact number is haversine's, the point is that it is the
    // user's own distance and not NEAR.distanceMeters (7, the server's, from the query centre).
    expect(props.distanceFromFixMeters).toBeGreaterThan(0)
    expect(props.distanceFromFixMeters).toBeLessThan(UNLOCK_RADIUS_METERS)

    wrapper.unmount()
  })

  it('tells the sheet it has no measurement once the fix is too old to act on', async () => {
    const wrapper = await mountWithPois()
    await emitFix(AT_POI.lat, AT_POI.lon, 12)
    advanceClock(LONG_STALE_MS)

    tapNear()
    await flushPromises()

    // Both halves, together: a null accuracy is what makes the sheet say "Czekam na pomiar
    // pozycji", and a null distance is what stops it printing a number that contradicts that.
    const props = sheetProps(wrapper)
    expect(props.accuracyMeters).toBeNull()
    expect(props.distanceFromFixMeters).toBeNull()

    wrapper.unmount()
  })

  it('re-reads the fix age every time the sheet opens, not once', async () => {
    const wrapper = await mountWithPois()
    await emitFix(AT_POI.lat, AT_POI.lon, 12)

    tapNear()
    await flushPromises()
    expect(sheetProps(wrapper).accuracyMeters).toBe(12)

    // Close, let the fix go stale with no new one arriving (geolocation that answers once and then
    // stops, or a paused watch under KeepAlive), reopen. A verdict cached from the first open would
    // survive here, and the sheet would keep quoting a position the gate has already disowned.
    queryRenderedFeatures.mockReturnValue([])
    clickListener?.({ point: { x: 400, y: 400 } })
    await flushPromises()
    expect(selection(wrapper)).toBeNull()

    advanceClock(LONG_STALE_MS)
    tapNear()
    await flushPromises()

    expect(sheetProps(wrapper).accuracyMeters).toBeNull()
    expect(sheetProps(wrapper).distanceFromFixMeters).toBeNull()

    wrapper.unmount()
  })
})
