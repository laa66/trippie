<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Ustawienia</q-toolbar-title>
        <q-space />
        <q-btn flat to="/map">Mapa</q-btn>
      </q-toolbar>
    </q-header>
    <q-page-container>
      <q-page class="p-4">
        <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
        <q-list>
          <q-item-label header>Domyślny tryb treści</q-item-label>
          <q-option-group
            type="radio"
            :model-value="defaultContentMode"
            :options="modeOptions"
            @update:model-value="onModeChange"
          />
        </q-list>
        <q-list>
          <q-item-label header>Kategorie</q-item-label>
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
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import {
  QLayout,
  QHeader,
  QToolbar,
  QToolbarTitle,
  QSpace,
  QBtn,
  QPageContainer,
  QPage,
  QList,
  QItem,
  QItemSection,
  QItemLabel,
  QOptionGroup,
  QToggle,
} from 'quasar'
import { CATEGORY_SLUGS, CATEGORY_LABELS, useCategorySelection } from '@/composables/useCategorySelection'
import { CONTENT_MODES, type ContentMode } from '@/lib/settingsApi'

const MODE_LABELS: Record<ContentMode, string> = {
  TEXT: 'Tekst',
  AUDIO: 'Audio',
  BOTH: 'Tekst i audio',
}

const modeOptions = CONTENT_MODES.map((mode) => ({ value: mode, label: MODE_LABELS[mode] }))

const { defaultContentMode, error, isSelected, toggle, setContentMode } = useCategorySelection()

function onModeChange(mode: ContentMode) {
  if (mode !== defaultContentMode.value) {
    setContentMode(mode)
  }
}
</script>
