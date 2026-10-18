<template>
  <q-layout view="hHh lpR fFf">
    <!-- The notch insets live on `.q-header` in theme.css (P16), so every header gets them and
         this one carries only its own elevation. QLayout re-measures the header when it grows and
         offsets the page from it, so the map follows by itself. -->
    <q-header class="shadow-raised">
      <q-toolbar>
        <q-toolbar-title class="text-xl font-semibold tracking-tight">Mapa</q-toolbar-title>
        <q-space />
        <!-- `min-h-11` + `px-2` instead of `dense`: dense gives the narrow look by shrinking the
             whole button, and Quasar's own default lands a flat text button at 36px - both
             under the frozen 44px floor for a touch target. -->
        <q-btn flat no-caps class="min-h-11 px-2" to="/settings" label="Ustawienia" />
        <logout-button />
        <attribution-info />
      </q-toolbar>
    </q-header>
    <q-page-container>
      <q-page>
        <!-- Anchored to the page box: QPage is sized by a JS-computed `min-height` (viewport
             minus the header/footer offsets) and never gets a definite height, so a percentage
             height would resolve against `auto` and collapse to 0. `.q-page` is position:relative,
             so an absolute inset gives the map the full page box without any JS. -->
        <div ref="mapEl" class="absolute inset-0" />
        <div
          v-if="geoError"
          class="absolute inset-x-4 top-4 z-10 ml-[env(safe-area-inset-left,0px)] mr-[env(safe-area-inset-right,0px)] flex justify-center"
        >
          <!-- Same notice component as the form screens, deliberately: the message is the same
               kind of thing (a non-blocking degraded state) and the tint it carries is opaque,
               which is what makes it readable over map tiles. The shadow is what a floating
               element needs and a notice inside a card does not. -->
          <app-notice variant="warning" role="status" class="max-w-sm shadow-raised">
            {{ geoError }}
          </app-notice>
        </div>
        <q-page-sticky position="bottom-right" :offset="[16, 16]">
          <q-btn
            fab
            round
            color="primary"
            class="shadow-raised mr-[env(safe-area-inset-right,0px)] mb-[env(safe-area-inset-bottom,0px)]"
            :icon="symRoundedTune"
            aria-label="Filtr kategorii"
            @click="isFilterOpen = true"
          />
        </q-page-sticky>
        <category-filter v-model="isFilterOpen" />
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { onActivated, onDeactivated, onMounted, onUnmounted, ref, useTemplateRef, watch } from 'vue'
import {
  QLayout,
  QHeader,
  QToolbar,
  QToolbarTitle,
  QSpace,
  QBtn,
  QPageContainer,
  QPage,
  QPageSticky,
} from 'quasar'
import { symRoundedTune } from '@quasar/extras/material-symbols-rounded'
import { Map as MapLibreMap, Marker, type MapMouseEvent } from 'maplibre-gl'
import { useGeolocation, WROCLAW_FALLBACK, type GeoFix } from '@/composables/useGeolocation'
import { useNearbyPois, type UseNearbyPois } from '@/composables/useNearbyPois'
import { useNearbyPoiLayer, type UseNearbyPoiLayer } from '@/composables/useNearbyPoiLayer'
import { useCategorySelection } from '@/composables/useCategorySelection'
import { createUnlockClient, type UnlockClient } from '@/lib/unlockClient'
import type { UnlockResponse } from '@/lib/unlockApi'
import { POI_LAYER_ID, hitTestBox, pickPoiHit, type PoiHit } from '@/lib/poiLayer'
import type { NearbyItem } from '@/lib/nearbyApi'
import { tileStyleUrl } from '@/lib/tiles'
import AppNotice from '@/components/ui/AppNotice.vue'
import CategoryFilter from '@/components/CategoryFilter.vue'
import AttributionInfo from '@/components/AttributionInfo.vue'
import LogoutButton from '@/components/LogoutButton.vue'

/**
 * Basemap attribution (M1-12, a licence condition — not decoration).
 *
 * The tileserver's own TileJSON already carries the full, linked notice, and MapLibre's
 * AttributionControl drops any entry that is a SUBSTRING of another one (a raw String.includes on
 * the HTML). Our previous plain-text '© OpenMapTiles © OpenStreetMap contributors' was not a
 * substring of the linked HTML the tileserver serves, so the control rendered both and the line
 * read twice over. Supplying a substring instead keeps the safety net — if the tileserver ever
 * stops declaring an attribution, this is still shown on its own — while the duplicate disappears
 * the moment the source declares one. The failure mode if the tileserver changes its wording is a
 * visible duplicate again, never a missing notice, which is the right way round for a legal
 * requirement. The `&copy;` entity, not '©', is what makes it a substring; MapView.spec.ts pins
 * the relation against the tileserver's exact string, because both the literal sign and a
 * '… (ODbL)' suffix were measured rendering the attribution twice.
 */
const OSM_ATTRIBUTION = '&copy; OpenStreetMap contributors'

// Load-bearing: App.vue's <keep-alive :include="['MapView']"> matches on the component name.
defineOptions({ name: 'MapView' })

/**
 * Unlock error suppression: after this many CONSECUTIVE failed attempts the path goes silent for
 * {@link UNLOCK_ERROR_COOLDOWN_MS}, and any answer that came back from the server clears the
 * streak.
 *
 * Why anything at all: `unlockClient` deliberately does not swallow its exceptions — its `finally`
 * has to free the in-flight slot so a `refused` POI can be offered again once the user has walked
 * closer — so the loop only materialises here, where the attempt is driven by the ~1 Hz fix
 * stream. Measured in M3-07: a permanent 400 produced one request per fix, 20 requests for 20
 * fixes.
 *
 * Why a STREAK and not silence after the first error: a single stray 5xx, or a 401 landing while
 * the refresh rotates, would otherwise mute unlocking for the whole cooldown — ~40 m of walking,
 * which is about the entire window a POI stays in range for. Three failures in a row is the signal
 * that the endpoint rather than the moment is broken. The cooldown then bounds the request RATE
 * instead of giving up, and because the client keeps asking after each silence, the first answer it
 * gets resets the streak — no other event has to revive it.
 *
 * `refused` ids are an ANSWER, not a failure: at the radius edge they are the expected one (see
 * UNLOCK_RADIUS_METERS), so a 200 clears the streak whatever is in it and arms nothing.
 */
const UNLOCK_ERROR_STREAK_LIMIT = 3
const UNLOCK_ERROR_COOLDOWN_MS = 30_000

/**
 * How old a fix may be when it is offered for unlocking.
 *
 * {@link offerUnlock} has TWO entry points and only one of them is a position measurement: the
 * other is the `nearby` answer landing, which happens on a pan, a category change, a reactivation
 * and on the refresh after a grant. Nothing clears `fix` either — neither a later geolocation
 * error nor `stop()` (the watch is paused whenever this view is hidden) — so without this bound a
 * fix measured long ago would keep being sent as the user's current position. At the ~1 Hz
 * `watchPosition` rate this never touches a normal walk: a fix is milliseconds old when either
 * entry point offers it, and 30 s is two orders of magnitude of slack on that.
 *
 * This is APPLICATION HONESTY, not a security control. The server is never told when the position
 * was measured, so a hand-written `curl` asserting a position still passes the 50 m gate on the
 * server's own terms — that is intended, and M3-10 verifies it deliberately. What this closes is
 * our own client claiming a position nothing has measured recently.
 */
const MAX_FIX_AGE_MS = 30_000

const isFilterOpen = ref(false)
const mapEl = useTemplateRef<HTMLDivElement>('mapEl')
/**
 * The POI the user last tapped, or null. Internal to this view: the sheet that renders it (M3-09)
 * goes into the template below, next to `CategoryFilter`, and binds to this through `:poi` /
 * `@close` — so there is nothing here for a parent to read, and nothing to expose. It always holds
 * an item straight out of the latest `nearby` response, so the lock state it shows is the
 * server's, never a local guess.
 */
const selectedPoi = ref<NearbyItem | null>(null)

let map: MapLibreMap | null = null
let userMarker: Marker | null = null
let nearby: UseNearbyPois | null = null
let poiLayer: UseNearbyPoiLayer | null = null
let unlock: UnlockClient | null = null
let unlockAbort: AbortController | null = null
let unlockMutedUntil = 0
let unlockErrorStreak = 0
let firstFixDone = false
// Set by onDeactivated, consumed by onActivated: the two are not symmetric, because Vue also
// fires `activated` immediately after the initial mount. See onActivated.
let wasDeactivated = false

const { position, isFallback, error: geoError, fix, start, stop } = useGeolocation()
// The module-level ref itself, deliberately: `requireProximity` is read off it at the moment of
// each attempt (below), because the user can flip the toggle in /settings and come back without
// MapView ever being remounted.
const { requireProximity } = useCategorySelection()

// Built in JS because MapLibre takes a DOM element, not a template — but the colours still come
// from the token layer (`bg-primary`, `border-ink-inverse`), so the marker re-themes with
// everything else instead of pinning two literals inside a function.
function createUserMarkerElement(): HTMLElement {
  const wrapper = document.createElement('div')
  wrapper.className = 'relative flex h-12 w-12 items-center justify-center'

  const ring = document.createElement('div')
  ring.className = 'absolute h-12 w-12 rounded-full border-4 border-primary opacity-0 animate-map-pulse'

  const core = document.createElement('div')
  core.className =
    'absolute z-10 h-6 w-6 rounded-full border-[3px] border-ink-inverse bg-primary shadow-raised'

  wrapper.append(ring, core)
  return wrapper
}

function isAbortError(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError'
}

/**
 * Offers the POIs this fix may unlock. Takes the fix as an argument rather than reading the ref,
 * so what is evaluated is the value the watcher fired for.
 *
 * The policy is built HERE, per attempt, and never handed to the client once at construction: the
 * toggle lives in a module-level ref and flips without a remount, so a captured copy would keep
 * enforcing the old setting for the rest of the session.
 *
 * The three gates below are the ones that depend on WHEN we are, which is why they are here and
 * not in `proximity.ts`: that module stays a pure function of a position and a POI list, with no
 * clock injected into it. The suppression window and the fix age ({@link MAX_FIX_AGE_MS}) read the
 * wall clock; whether a request has already gone out for this very fix is the client's own memory,
 * keyed on the fix timestamp.
 *
 * On a grant we re-read `nearby` instead of patching the features we already have. The rows are
 * the server's, they are rare (a handful per walk), and a client-side unlock cache tied to the
 * view is exactly what the milestone forbids — the row also outlives the viewport and the category
 * filter, which only a re-read reflects.
 */
async function offerUnlock(current: GeoFix | null): Promise<void> {
  if (!unlock || !nearby || current === null) return
  if (Date.now() < unlockMutedUntil) return
  if (Date.now() - current.timestamp > MAX_FIX_AGE_MS) return

  // Only the request is guarded. Anything after it (the re-read, the bookkeeping) is OUR code: a
  // bug there must not be counted as a failing endpoint, and must not be swallowed into a silence
  // that looks like one.
  let response: UnlockResponse | null
  try {
    response = await unlock.attemptUnlock(
      current,
      nearby.items.value,
      { requireProximity: requireProximity.value },
      unlockAbort?.signal,
    )
  } catch (err) {
    // An abort is the view being torn down, not a failing endpoint.
    if (!isAbortError(err)) {
      unlockErrorStreak += 1
      if (unlockErrorStreak >= UNLOCK_ERROR_STREAK_LIMIT) {
        unlockErrorStreak = 0
        unlockMutedUntil = Date.now() + UNLOCK_ERROR_COOLDOWN_MS
      }
    }
    return
  }

  // Nothing went out: no candidates for this fix, or a request has already been sent for it (the
  // client's own per-fix memory). Neither is an answer, so neither clears the streak.
  if (response === null) return
  unlockErrorStreak = 0

  // Re-checked AFTER the await: `onUnmounted` nulls this while a request can still be in flight,
  // and the narrowing at the top of the function is long stale by here.
  if (nearby === null) return
  if (response.unlocked.length > 0) {
    nearby.refresh()
  }
}

/**
 * Resolves a tap to at most one POI. A 6 px dot is a 12 px target against a frozen 44 px floor, so
 * the query is a box around the tap point ({@link hitTestBox}) rather than the point itself — the
 * dot keeps its designed radius and the map keeps its single layer.
 *
 * LOCKED POIs are selectable too (frozen decision #5): a tap that does nothing reads as a broken
 * app, not as a locked POI. The sheet shows a different thing, it is not absent.
 */
function onMapClick(event: MapMouseEvent): void {
  if (!map || !map.getLayer(POI_LAYER_ID)) return

  const hits: PoiHit[] = []
  for (const feature of map.queryRenderedFeatures(hitTestBox(event.point), {
    layers: [POI_LAYER_ID],
  })) {
    const osmId: unknown = feature.properties?.id
    if (typeof osmId !== 'string' || feature.geometry.type !== 'Point') continue
    const [lon, lat] = feature.geometry.coordinates
    const centre = map.project([lon, lat])
    hits.push({
      osmId,
      distancePx: Math.hypot(centre.x - event.point.x, centre.y - event.point.y),
    })
  }

  const picked = pickPoiHit(hits)
  // Nothing under the box: a tap on bare map dismisses the selection rather than leaving a sheet
  // open over a POI the user has moved away from.
  selectedPoi.value = picked === null ? null : findItem(picked)
}

function findItem(osmId: string): NearbyItem | null {
  return nearby?.items.value.find((item) => item.id === osmId) ?? null
}

// Everything that must exist exactly once lives here: under KeepAlive this runs on the first
// visit only, so no listener, timer, observer or subscription can be opened twice.
onMounted(() => {
  if (!mapEl.value) return

  map = new MapLibreMap({
    container: mapEl.value,
    style: tileStyleUrl(),
    center: [WROCLAW_FALLBACK.longitude, WROCLAW_FALLBACK.latitude],
    zoom: 13,
    attributionControl: {
      compact: false,
      customAttribution: OSM_ATTRIBUTION,
    },
  })

  nearby = useNearbyPois(map)
  poiLayer = useNearbyPoiLayer(map, nearby.items)
  unlock = createUnlockClient()
  unlockAbort = new AbortController()

  map.on('click', onMapClick)

  // Re-points the selection at the item the LATEST response carries, so an unlock that landed
  // (here or on another device) reaches an open sheet. The id is the only thing kept across the
  // refetch; everything shown comes from the new payload. A POI that left the result — panned
  // away, or filtered out — has no dot behind it any more, so the selection goes with it.
  watch(nearby.items, () => {
    if (selectedPoi.value !== null) {
      selectedPoi.value = findItem(selectedPoi.value.id)
    }
    // The SECOND entry point into the same attempt — one function, called from both watchers, so
    // the dedupe, the policy read and the error cooldown are shared rather than duplicated.
    //
    // It is needed because the first real fix is also what triggers the first nearby query: when
    // that fix is evaluated there are no POIs yet, so without this the earliest possible unlock
    // would be the NEXT fix — and a device standing still that produces exactly one fix would
    // never unlock anything at all. `fix === null` still means zero requests, which is the
    // fallback trap approached from this side: POIs arriving while the position is the fallback
    // offer nothing, because there is no GeoFix to offer them with.
    void offerUnlock(fix.value)
  })

  start()
})

// /map is cached (App.vue), so navigating away deactivates this view instead of destroying it.
// Deliberately kept alive: the map and its WebGL context, the POI source/layer and the items
// already fetched — that cache is the whole point. Paused: the GPS watch, because holding
// watchPosition on a screen nobody is looking at only drains the battery. `start()` is
// idempotent while a watch id is held, so the activated hook Vue also fires right after the
// initial mount is a no-op; the map guard keeps it that way on the path where onMounted bailed.
onActivated(() => {
  if (!map) return
  start()

  // A RE-activation, not the activation that follows the first mount — the flag is the only thing
  // that tells them apart, and the mount path must not query: the first real fix does that, and a
  // query from here would race it.
  //
  // Why re-read at all: the GPS watch was paused while hidden, so nothing could have refreshed the
  // lock state, and a row may have been written on another device in the meantime (the unlock is
  // server state, and `knownUnlocked` is only a dedupe memory). Only the POIs currently rendered
  // have a state that can have gone stale under us; with none, there is nothing to re-read and the
  // next fix, pan or filter change queries anyway.
  if (wasDeactivated) {
    wasDeactivated = false
    if (nearby !== null && nearby.items.value.length > 0) {
      nearby.refresh()
    }
  }
})

// The dialog is teleported to <body>, so it does not go away with the view's DOM: left open, it
// would hang over whatever renders next (the login screen, when a revoked session routes there).
onDeactivated(() => {
  stop()
  isFilterOpen.value = false
  // Same reason as the dialog above: the sheet M3-09 binds to this is teleported too, so a
  // selection left standing would float over whatever renders next.
  selectedPoi.value = null
  wasDeactivated = true
})

// A position MEASUREMENT, which is one of the two entry points into an attempt (the other is the
// `nearby` answer landing, above). Whichever one fires, what is offered is a `GeoFix` and NEVER
// `position`: `position` legitimately holds WROCLAW_FALLBACK, which sits in the middle of the
// old-town POI cluster, and a `GeoFix` cannot be built from it at all (see proximity.ts and
// unlockFallbackTrap.spec.ts). Because the other entry point can fire long after the last
// measurement, `offerUnlock` bounds the fix's AGE (see MAX_FIX_AGE_MS) — this watcher is the only
// thing that ever produces a new one. The marker and the camera below stay on `position` — that is
// the one thing the fallback is for.
watch(fix, (current) => {
  void offerUnlock(current)
})

watch(
  position,
  (pos) => {
    if (!map) return
    const lngLat: [number, number] = [pos.longitude, pos.latitude]

    if (!userMarker) {
      userMarker = new Marker({ element: createUserMarkerElement() }).setLngLat(lngLat).addTo(map)
    } else {
      userMarker.setLngLat(lngLat)
    }

    if (!isFallback.value) {
      map.flyTo({ center: lngLat, zoom: 15 })
      if (!firstFixDone) {
        firstFixDone = true
        nearby?.refresh()
      }
    }
  },
)

// Under KeepAlive this no longer runs on navigation — only when the cache is dropped (app
// teardown) or when MapView is mounted outside the cache. Still the single full teardown.
onUnmounted(() => {
  stop()
  poiLayer?.stop()
  poiLayer = null
  nearby?.stop()
  nearby = null
  unlockAbort?.abort()
  unlockAbort = null
  unlock = null
  selectedPoi.value = null
  userMarker?.remove()
  userMarker = null
  map?.off('click', onMapClick)
  map?.remove()
  map = null
})
</script>

<style scoped>
/*
 * MapLibre renders the attribution control itself, so it is the one piece of map chrome that
 * cannot be dressed with utility classes. It is skinned here rather than in theme.css because it
 * belongs to this screen only, and scoped (`:deep`) because the control is created inside the map
 * container at runtime. Scoped styles are unlayered, so they beat maplibre's own stylesheet, which
 * theme.css pins into @layer base.
 *
 * CAREFUL with what goes in here: unlayered also means this block outranks @layer utilities, so a
 * rule written on a CLASS would silently beat Tailwind on this screen and nowhere else. Every
 * selector below is a `.maplibregl-*` one the framework owns and we never write by hand.
 *
 * Every value is a token. The notice has to be legible on top of whatever tiles are underneath it,
 * so the background is opaque surface rather than maplibre's translucent white: ink-muted on
 * surface measures 5.65:1. 11px is below our caption step on purpose — it is a permanent legal
 * line, not screen copy, and it has to stay on one line next to the FAB — so the ratio is what
 * has to hold at that size, and it does.
 */
:deep(.maplibregl-ctrl-bottom-right) {
  right: env(safe-area-inset-right, 0px);
  bottom: env(safe-area-inset-bottom, 0px);
}

:deep(.maplibregl-ctrl-bottom-right .maplibregl-ctrl-attrib) {
  /* 80px right = the 56px FAB plus its 16px offset and a little air, so the line never slides
     underneath it; the remaining 302px is what the notice has to fit into on a 390px screen. */
  margin: 0 80px 10px 8px;
  padding: 3px 10px;
  border-radius: var(--radius-pill);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-card);
  color: var(--app-ink-muted);
  font-family: var(--font-sans);
  font-size: 11px;
  line-height: 1.5;
}

/* The OSM copyright link is part of the required attribution, so it has to look like a link:
   maplibre removes the underline and only restores it on :hover, which a touch device never
   reaches, and the colour alone is the surrounding colour. */
:deep(.maplibregl-ctrl-attrib a) {
  color: var(--app-ink-muted);
  text-decoration: underline;
}
</style>
