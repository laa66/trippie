<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Mapa</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :scroll-y="false">
      <div ref="mapEl" class="map-container" />
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent } from '@ionic/vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const mapEl = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null

onMounted(() => {
  if (!mapEl.value) return

  map = L.map(mapEl.value).setView([51.1079, 17.0385], 13)

  L.tileLayer('https://{s}.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}{r}.png', {
  attribution: '© <a href="https://carto.com/">CARTO</a>',
  maxZoom: 19,
}).addTo(map)

  // poczekaj aż Ionic skończy renderować kontener
  setTimeout(() => {
    map?.invalidateSize()
  }, 100)
})
onUnmounted(() => {
  map?.remove()
  map = null
})
</script>

<style>
/* BEZ scoped — musimy nadpisać shadow DOM Ionic */
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
</style>