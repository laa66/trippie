<template>
  <q-dialog v-model="isOpen">
    <q-card>
      <q-toolbar>
        <q-toolbar-title>Kategorie</q-toolbar-title>
        <q-space />
        <q-btn flat label="Zamknij" @click="isOpen = false" />
      </q-toolbar>
      <q-list>
        <q-item v-for="slug in CATEGORY_SLUGS" :key="slug">
          <q-item-section>
            <q-toggle
              :model-value="isSelected(slug)"
              :label="CATEGORY_LABELS[slug]"
              @update:model-value="toggle(slug)"
            />
          </q-item-section>
        </q-item>
      </q-list>
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import {
  QDialog,
  QCard,
  QToolbar,
  QToolbarTitle,
  QSpace,
  QBtn,
  QList,
  QItem,
  QItemSection,
  QToggle,
} from 'quasar'
import { CATEGORY_SLUGS, CATEGORY_LABELS, useCategorySelection } from '@/composables/useCategorySelection'

const isOpen = defineModel<boolean>({ required: true })

const { isSelected, toggle } = useCategorySelection()
</script>
