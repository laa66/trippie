<template>
  <!-- aria-labelledby, not a second string: the heading already names the dialog, and a copy of
       it in an aria-label would be the thing that drifts. -->
  <q-dialog v-model="isOpen" aria-labelledby="category-filter-title">
    <q-card class="w-full max-w-sm rounded-card bg-surface pb-2 shadow-card">
      <!-- Not a q-toolbar: inside a dialog card `.q-toolbar__title` is an ellipsised flex child,
           and at this width it cut "Kategorie" down to "Kat…". A heading and the close button in
           a plain row give the title the whole line it needs and match the card headers used on
           the settings screen. -->
      <div class="flex flex-nowrap items-center gap-2 px-4 pt-4 pb-1">
        <h2 id="category-filter-title" class="min-w-0 flex-1 text-xl font-semibold tracking-tight text-ink">
          Kategorie
        </h2>
        <!-- `min-h-11 px-2`, not `dense`: Quasar's default flat button is 36px tall, under the
             frozen 44px touch floor, and dense would make it smaller still. -->
        <q-btn flat no-caps class="min-h-11 px-2" color="primary" label="Zamknij" @click="isOpen = false" />
      </div>
      <q-list>
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
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import { QDialog, QCard, QBtn, QList, QItem, QItemSection, QToggle } from 'quasar'
import { CATEGORY_SLUGS, CATEGORY_LABELS, useCategorySelection } from '@/composables/useCategorySelection'

const isOpen = defineModel<boolean>({ required: true })

const { isSelected, toggle } = useCategorySelection()
</script>
