<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title class="text-xl font-semibold tracking-tight">Ustawienia</q-toolbar-title>
        <q-space />
        <q-btn flat no-caps class="min-h-11 px-2" to="/map">Mapa</q-btn>
      </q-toolbar>
    </q-header>
    <q-page-container>
      <q-page class="mx-auto w-full max-w-md space-y-4 px-4 py-6">
        <app-notice v-if="error" variant="danger" role="alert">{{ error }}</app-notice>
        <q-list class="rounded-card bg-surface pb-2 shadow-card">
          <q-item-label header class="text-ink-muted">Domyślny tryb treści</q-item-label>
          <q-option-group
            class="px-2"
            aria-label="Domyślny tryb treści"
            type="radio"
            color="primary"
            :model-value="defaultContentMode"
            :options="modeOptions"
            @update:model-value="onModeChange"
          />
        </q-list>
        <q-list class="rounded-card bg-surface pb-2 shadow-card">
          <q-item-label header class="text-ink-muted">Odblokowywanie miejsc</q-item-label>
          <q-item>
            <q-item-section>
              <q-toggle
                class="w-full flex-nowrap justify-between"
                left-label
                color="primary"
                :model-value="requireProximity"
                label="Wymagaj zbliżenia do miejsca"
                @update:model-value="setRequireProximity"
              />
              <q-item-label caption class="text-ink-muted">
                Miejsca już odblokowane zostają odblokowane na stałe, także po ponownym włączeniu wymogu.
              </q-item-label>
            </q-item-section>
          </q-item>
        </q-list>
        <q-list class="rounded-card bg-surface pb-2 shadow-card">
          <q-item-label header class="text-ink-muted">Kategorie</q-item-label>
          <q-item v-for="slug in CATEGORY_SLUGS" :key="slug">
            <q-item-section>
              <q-toggle
                class="w-full flex-nowrap justify-between"
                left-label
                color="primary"
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
import AppNotice from '@/components/ui/AppNotice.vue'
import { CATEGORY_SLUGS, CATEGORY_LABELS, useCategorySelection } from '@/composables/useCategorySelection'
import { CONTENT_MODES, type ContentMode } from '@/lib/settingsApi'

const MODE_LABELS: Record<ContentMode, string> = {
  TEXT: 'Tekst',
  AUDIO: 'Audio',
  BOTH: 'Tekst i audio',
}

const modeOptions = CONTENT_MODES.map((mode) => ({ value: mode, label: MODE_LABELS[mode] }))

const { defaultContentMode, requireProximity, error, isSelected, toggle, setContentMode, setRequireProximity } =
  useCategorySelection()

function onModeChange(mode: ContentMode) {
  if (mode !== defaultContentMode.value) {
    setContentMode(mode)
  }
}
</script>
