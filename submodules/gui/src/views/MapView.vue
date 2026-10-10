<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Mapa</q-toolbar-title>
        <q-space />
        <q-btn flat to="/settings" label="Ustawienia" />
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
          class="absolute left-1/2 top-4 z-10 -translate-x-1/2 rounded-full bg-white/95 px-4 py-2 text-sm font-medium text-dark shadow-md"
        >
          {{ geoError }}
        </div>
        <q-page-sticky position="bottom-right" :offset="[16, 16]">
          <q-btn
            fab
            round
            color="primary"
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
import CategoryFilter from '@/components/CategoryFilter.vue'
import AttributionInfo from '@/components/AttributionInfo.vue'
import LogoutButton from '@/components/LogoutButton.vue'

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

function createUserMarkerElement(): HTMLElement {
  const wrapper = document.createElement('div')
  wrapper.className = 'relative flex h-12 w-12 items-center justify-center'

  const ring = document.createElement('div')
  ring.className = 'absolute h-12 w-12 rounded-full border-4 border-primary opacity-0 animate-map-pulse'

  const core = document.createElement('div')
  core.className = 'absolute z-10 h-6 w-6 rounded-full border-[3px] border-white bg-primary shadow-md'

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
      customAttribution: '© OpenMapTiles © OpenStreetMap contributors',
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
