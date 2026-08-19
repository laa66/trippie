<template>
  <ion-modal :is-open="isOpen" @did-dismiss="$emit('update:isOpen', false)">
    <ion-header>
      <ion-toolbar>
        <ion-title>Kategorie</ion-title>
        <ion-buttons slot="end">
          <ion-button @click="$emit('update:isOpen', false)">Zamknij</ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <ion-list>
        <ion-item v-for="slug in CATEGORY_SLUGS" :key="slug">
          <ion-toggle :checked="isSelected(slug)" @ion-change="toggle(slug)">
            {{ CATEGORY_LABELS[slug] }}
          </ion-toggle>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-modal>
</template>

<script setup lang="ts">
import {
  IonModal,
  IonHeader,
  IonToolbar,
  IonTitle,
  IonButtons,
  IonButton,
  IonContent,
  IonList,
  IonItem,
  IonToggle,
} from '@ionic/vue'
import { CATEGORY_SLUGS, CATEGORY_LABELS, useCategorySelection } from '@/composables/useCategorySelection'

defineProps<{ isOpen: boolean }>()
defineEmits<{ 'update:isOpen': [value: boolean] }>()

const { isSelected, toggle } = useCategorySelection()
</script>
