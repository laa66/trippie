<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Ustawienia</ion-title>
        <ion-buttons slot="end">
          <ion-button router-link="/map">Mapa</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content class="ion-padding">
      <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
      <ion-list>
        <ion-radio-group :value="defaultContentMode" @ion-change="onModeChange($event.detail.value)">
          <ion-list-header>Domyślny tryb treści</ion-list-header>
          <ion-item v-for="mode in CONTENT_MODES" :key="mode">
            <ion-radio :value="mode">{{ MODE_LABELS[mode] }}</ion-radio>
          </ion-item>
        </ion-radio-group>
      </ion-list>
      <ion-list>
        <ion-list-header>Kategorie</ion-list-header>
        <ion-item v-for="slug in CATEGORY_SLUGS" :key="slug">
          <ion-toggle :checked="isSelected(slug)" @ion-change="toggle(slug)">
            {{ CATEGORY_LABELS[slug] }}
          </ion-toggle>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import {
  IonPage,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonButtons,
  IonButton,
  IonContent,
  IonList,
  IonListHeader,
  IonItem,
  IonRadioGroup,
  IonRadio,
  IonToggle,
} from '@ionic/vue'
import { CATEGORY_SLUGS, CATEGORY_LABELS, useCategorySelection } from '@/composables/useCategorySelection'
import { CONTENT_MODES, type ContentMode } from '@/lib/settingsApi'

const MODE_LABELS: Record<ContentMode, string> = {
  TEXT: 'Tekst',
  AUDIO: 'Audio',
  BOTH: 'Tekst i audio',
}

const { defaultContentMode, error, isSelected, toggle, setContentMode } = useCategorySelection()

function onModeChange(mode: ContentMode) {
  if (mode !== defaultContentMode.value) {
    setContentMode(mode)
  }
}
</script>
