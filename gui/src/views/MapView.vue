<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Mapa</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :scroll-y="false">
      <div v-if="isLoading" class="map-loader">Ładowanie punktów...</div>
      
      <div ref="mapEl" class="map-container" />
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent } from '@ionic/vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

import { useLocations } from '@/composables/useLocations'

const mapEl = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null
let userMarker: L.Marker | null = null

const apiMarkers: L.Marker[] = []

const {
  locations,
  isLoading,
  currentPosition,
  startLocationWatch,
  stopLocationWatch,
} = useLocations()

const userError = ref<string | null>(null)

const userLocationIcon = L.divIcon({
  className: 'user-location-marker',
  html: '<div class="pulse-core"></div><div class="pulse-ring"></div>',
  iconSize: [50, 50],
  iconAnchor: [25, 25],
})

const renderApiMarkers = () => {
  if (!map) return

  apiMarkers.forEach(marker => marker.remove())
  apiMarkers.length = 0

  locations.value.forEach((loc) => {
    const marker = L.marker([loc.latitude, loc.longitude])
      .addTo(map!)
      .bindPopup(`<b>${loc.name}</b>`)

    apiMarkers.push(marker)
  })
}

watch(locations, () => {
  renderApiMarkers()
}, { deep: true })

watch(currentPosition, (position) => {
  if (!map || !position) return

  const { latitude, longitude } = position
  if (userMarker) {
    userMarker.setLatLng([latitude, longitude])
    userMarker.setZIndexOffset(1000)
  } else {
    userMarker = L.marker([latitude, longitude], {
      icon: userLocationIcon,
      zIndexOffset: 1000,
    }).addTo(map!)
  }

  map.setView([latitude, longitude], 15)
}, { deep: true })

const initUserLocation = () => {
  if (!navigator.geolocation) {
    userError.value = 'Twoja przeglądarka nie wspiera geolokalizacji.'
    return
  }

  startLocationWatch()
}

onMounted(() => {
  if (!mapEl.value) return

  map = L.map(mapEl.value).setView([51.1079, 17.0385], 13)

  L.tileLayer('https://{s}.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}{r}.png', {
    attribution: '© <a href="https://carto.com/">CARTO</a>',
    maxZoom: 19,
  }).addTo(map)

  setTimeout(() => {
    map?.invalidateSize()
    initUserLocation()
  }, 100)
})

onUnmounted(() => {
  stopLocationWatch()
  apiMarkers.forEach(marker => marker.remove())
  map?.remove()
  map = null
  userMarker = null
})
</script>

<style>
ion-content {
  --overflow: hidden;
}

.map-container {
  width: 100%;
  height: 100%;
  position: absolute;
  top: 0;
  left: 0;
  bottom: 0;
  right: 0;
}

/* Prosty styl dla indykatora ładowania nad mapą */
.map-loader {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000; /* Musi być wyżej niż mapa Leafleta */
  background: white;
  padding: 8px 16px;
  border-radius: 20px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.15);
  font-size: 14px;
  font-weight: 500;
}

/* Style dla punktu GPS użytkownika */
.user-location-marker {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 50px;
  height: 50px;
}
.pulse-core {
  width: 24px;
  height: 24px;
  background-color: #3880ff;
  border: 3px solid white;
  border-radius: 50%;
  position: absolute;
  z-index: 2;
  box-shadow: 0 0 8px rgba(0, 0, 0, 0.3);
}
.pulse-ring {
  width: 48px;
  height: 48px;
  border: 4px solid #3880ff;
  border-radius: 50%;
  position: absolute;
  z-index: 1;
  animation: map-pulse 2s ease-out infinite;
  opacity: 0;
}
@keyframes map-pulse {
  0% { transform: scale(0.5); opacity: 0.5; }
  70% { transform: scale(1.2); opacity: 0.2; }
  100% { transform: scale(1.4); opacity: 0; }
}
</style>