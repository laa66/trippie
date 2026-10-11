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
import { Map as MapLibreMap, Marker } from 'maplibre-gl'
import { useGeolocation, WROCLAW_FALLBACK } from '@/composables/useGeolocation'
import { useNearbyPois, type UseNearbyPois } from '@/composables/useNearbyPois'
import { useNearbyPoiLayer, type UseNearbyPoiLayer } from '@/composables/useNearbyPoiLayer'
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

const isFilterOpen = ref(false)
const mapEl = useTemplateRef<HTMLDivElement>('mapEl')
let map: MapLibreMap | null = null
let userMarker: Marker | null = null
let nearby: UseNearbyPois | null = null
let poiLayer: UseNearbyPoiLayer | null = null
let firstFixDone = false

const { position, isFallback, error: geoError, start, stop } = useGeolocation()

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
})

// The dialog is teleported to <body>, so it does not go away with the view's DOM: left open, it
// would hang over whatever renders next (the login screen, when a revoked session routes there).
onDeactivated(() => {
  stop()
  isFilterOpen.value = false
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
  userMarker?.remove()
  userMarker = null
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
