<template>
  <q-dialog v-model="isOpen" position="bottom" aria-labelledby="poi-detail-title">
    <!-- A max() bottom padding rather than the flat pb-2 the other cards use: this is the repo's
         first position="bottom" dialog, and nothing above it pays the bottom inset. P16 in
         theme.css covers .q-header only, and Quasar's own rule is dead twice over here
         (--q-safe-area-inset-bottom is declared under body classes this project never sets, and
         .q-dialog__inner--bottom zeroes the padding anyway — which is also why this belongs on the
         card and not the inner: padding there would show a strip of backdrop under the sheet).
         Local for now because the design system is frozen in flight; M6 moves it into the semantic
         layer. NOTE: no class-shaped token in this comment — Tailwind 4 scans comments too, and an
         arbitrary-value utility written here is emitted into the built CSS as a broken rule. -->
    <q-card class="w-full max-w-sm rounded-card bg-surface pb-[max(0.5rem,env(safe-area-inset-bottom,0px))] shadow-card">
      <!-- Header, content area, footer: M4 swaps the middle and enables the footer button, the
           layout stays. -->
      <div class="flex flex-nowrap items-start gap-2 px-4 pt-4 pb-1">
        <div class="min-w-0 flex-1">
          <h2 id="poi-detail-title" class="text-xl font-semibold tracking-tight text-ink">
            {{ poi?.name ?? 'Miejsce bez nazwy' }}
          </h2>
          <p class="text-sm text-ink-muted">{{ subtitle }}</p>
        </div>
        <!-- `min-h-11 px-2`, not `dense`: see CategoryFilter. -->
        <q-btn flat no-caps class="min-h-11 px-2" color="primary" label="Zamknij" @click="isOpen = false" />
      </div>

      <div class="min-h-16 px-4 py-2">
        <app-notice v-if="content !== null" :variant="content.variant" role="status">
          {{ content.text }}
        </app-notice>
      </div>

      <div class="space-y-2 px-4 pt-1">
        <q-btn
          disable
          unelevated
          no-caps
          color="primary"
          class="min-h-11 w-full px-2"
          label="Przewodnik"
        />
        <app-notice variant="info" role="status">Przewodnik dostępny wkrótce</app-notice>
      </div>
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { QDialog, QCard, QBtn } from 'quasar'
import AppNotice from '@/components/ui/AppNotice.vue'
import { CATEGORY_LABELS, CATEGORY_SLUGS, type CategorySlug } from '@/composables/useCategorySelection'
import type { NearbyItem } from '@/lib/nearbyApi'
import { formatDistance, poiContentMessage } from '@/lib/poiDetail'

/**
 * `distanceFromFixMeters` is the distance from the user's own measured fix, handed in by MapView.
 * The name is load-bearing: `poi.distanceMeters` is also a number of metres, also on hand here, and
 * means something else entirely — the server measures it from the centre of the `nearby` query,
 * i.e. from wherever the map happens to be looking. A header that reads "how far I am from this
 * place" has to carry the same number the unlock gate works on, and with both props named
 * `distanceMeters` the wrong one would compile, typecheck and look right in review.
 *
 * `null` when there is no fix to measure from, and then no distance is shown at all rather than a
 * number that answers a different question. It comes from the same fix as `accuracyMeters`, so the
 * sheet can never show a distance while saying it has no measurement.
 */
const props = defineProps<{
  poi: NearbyItem | null
  accuracyMeters: number | null
  distanceFromFixMeters: number | null
  requireProximity: boolean
}>()
const emit = defineEmits<{ close: [] }>()

const isOpen = computed({
  get: () => props.poi !== null,
  set: (open: boolean) => {
    if (!open) emit('close')
  },
})

// item.category is a server string, not necessarily one of our slugs.
function categoryLabel(category: string): string | null {
  return (CATEGORY_SLUGS as readonly string[]).includes(category) ? CATEGORY_LABELS[category as CategorySlug] : null
}

const subtitle = computed(() => {
  if (props.poi === null) return ''
  const label = categoryLabel(props.poi.category)
  const distance =
    props.distanceFromFixMeters === null ? null : formatDistance(props.distanceFromFixMeters)
  return [label, distance].filter((part) => part !== null).join(' · ')
})

const content = computed(() =>
  props.poi === null
    ? null
    : poiContentMessage(props.poi.unlocked, props.accuracyMeters, props.requireProximity),
)
</script>
