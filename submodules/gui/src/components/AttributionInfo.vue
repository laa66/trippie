<template>
  <ion-buttons slot="end">
    <ion-button id="attribution-info-trigger" aria-label="Informacje o źródle danych">
      <ion-icon slot="icon-only" :icon="informationCircleOutline" />
    </ion-button>
  </ion-buttons>
  <ion-popover trigger="attribution-info-trigger" trigger-action="click">
    <ion-content class="ion-padding">
      <p>
        Dane o punktach zainteresowania (POI) pochodzą z OpenStreetMap i są udostępniane na
        licencji ODbL: © OpenStreetMap contributors.
      </p>
    </ion-content>
  </ion-popover>
</template>

<script setup lang="ts">
import { IonButtons, IonButton, IonIcon, IonPopover, IonContent } from '@ionic/vue'
import { informationCircleOutline } from 'ionicons/icons'
</script>
