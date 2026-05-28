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

// Importujemy Twoje composable
import { useLocations } from '@/composables/useLocations'

const mapEl = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null
let userMarker: L.Marker | null = null
let watchId: number | null = null

// Przechowujemy markery z bazy w tablicy, żeby móc je łatwo wyczyścić w razie potrzeby
const apiMarkers: L.Marker[] = []

// Wyciągamy potrzebne rzeczy z Twojego composable
const { locations, isLoading, fetchLocations } = useLocations()

// Stylizacja markera użytkownika (pulsująca kropka)
const userLocationIcon = L.divIcon({
  className: 'user-location-marker',
  html: '<div class="pulse-core"></div><div class="pulse-ring"></div>',
  iconSize: [20, 20],
  iconAnchor: [10, 10]
})

// Funkcja odpowiedzialna za renderowanie punktów z API na mapie
const renderApiMarkers = () => {
  if (!map) return

  // Najpierw czyścimy stare markery, jeśli jakieś były (zapobiega duplikacji)
  apiMarkers.forEach(marker => marker.remove())
  apiMarkers.length = 0

  // Renderujemy nowe punkty z bazy
  locations.value.forEach((loc) => {
    const marker = L.marker([loc.latitude, loc.longitude])
      .addTo(map!)
      .bindPopup(`<b>${loc.name}</b>`) // Po kliknięciu pokaże się nazwa miejsca

    apiMarkers.push(marker)
  })
}

// Obserwujemy tablicę locations. Kiedy zmieni się stan (dane przyjdą z API),
// watch automatycznie odpali funkcję rysującą je na mapie.
watch(locations, () => {
  renderApiMarkers()
}, { deep: true })

const getUserLocation = () => {
  if (!navigator.geolocation) return

  watchId = navigator.geolocation.watchPosition(
    (position) => {
      const { latitude, longitude } = position.coords
      if (!map) return

      if (userMarker) {
        userMarker.setLatLng([latitude, longitude])
      } else {
        userMarker = L.marker([latitude, longitude], { icon: userLocationIcon }).addTo(map)
        map.setView([latitude, longitude], 15)
      }
    },
    (error) => console.error('Błąd GPS:', error.message),
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }
  )
}

onMounted(async () => {
  if (!mapEl.value) return

  // Inicjalizacja mapy
  map = L.map(mapEl.value).setView([51.1079, 17.0385], 13)

  L.tileLayer('https://{s}.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}{r}.png', {
    attribution: '© <a href="https://carto.com/">CARTO</a>',
    maxZoom: 19,
  }).addTo(map)

  setTimeout(async () => {
    map?.invalidateSize()
    getUserLocation()
    
    // ODPALENIE COMPOSABLE: Strzelamy do API po punkty z bazy
    // Ponieważ funkcja jest asynchroniczna, poczeka na dane z backendu
    await fetchLocations()
  }, 100)
})

onUnmounted(() => {
  if (watchId !== null) {
    navigator.geolocation.clearWatch(watchId)
  }
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
}
.pulse-core {
  width: 12px;
  height: 12px;
  background-color: #3880ff;
  border: 2px solid white;
  border-radius: 50%;
  position: absolute;
  z-index: 2;
  box-shadow: 0 0 5px rgba(0, 0, 0, 0.3);
}
.pulse-ring {
  width: 24px;
  height: 24px;
  border: 3px solid #3880ff;
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