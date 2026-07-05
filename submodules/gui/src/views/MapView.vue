<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Mapa</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :scroll-y="false" class="[--overflow:hidden]">
      <div class="relative h-full w-full">
        <div ref="mapEl" class="absolute inset-0" />
        <div
          v-if="geoError"
          class="absolute left-1/2 top-4 z-10 -translate-x-1/2 rounded-full bg-white/95 px-4 py-2 text-sm font-medium text-dark shadow-md"
        >
          {{ geoError }}
        </div>
      </div>
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, useTemplateRef, watch } from 'vue'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent } from '@ionic/vue'
import { Map as MapLibreMap, Marker } from 'maplibre-gl'
import { useGeolocation, WROCLAW_FALLBACK } from '@/composables/useGeolocation'
import { tileStyleUrl } from '@/lib/tiles'

const mapEl = useTemplateRef<HTMLDivElement>('mapEl')
let map: MapLibreMap | null = null
let userMarker: Marker | null = null

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

onMounted(() => {
  if (!mapEl.value) return

  map = new MapLibreMap({
    container: mapEl.value,
    style: tileStyleUrl(),
    center: [WROCLAW_FALLBACK.longitude, WROCLAW_FALLBACK.latitude],
    zoom: 13,
    attributionControl: {
      customAttribution: '© OpenMapTiles © OpenStreetMap contributors',
    },
  })

  start()
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
    }
  },
)

onUnmounted(() => {
  stop()
  userMarker?.remove()
  userMarker = null
  map?.remove()
  map = null
})
</script>
