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

      <div ref="contentArea" tabindex="-1" class="min-h-16 space-y-2 px-4 py-2 outline-none">
        <app-notice v-if="statusMessage !== null" :variant="statusMessage.variant" role="status">
          {{ statusMessage.text }}
        </app-notice>
        <app-notice v-else-if="notice !== null" :variant="notice.variant" :role="notice.role">
          {{ notice.text }}
        </app-notice>
        <div v-else-if="progressText !== null" role="status" class="flex flex-nowrap items-center gap-2.5 text-sm text-ink-muted">
          <q-spinner color="primary" size="20px" aria-hidden="true" />
          <p>{{ progressText }}</p>
        </div>
        <template v-else-if="content.kind === 'completed'">
          <!-- The only thing announced on arrival: the guide text itself stays outside every live
               region (250 words read out at once is the failure), plain interpolation, never raw
               HTML (the text comes from a model). tabindex so a keyboard user can scroll it. -->
          <p v-if="acted" role="status" class="sr-only">Przewodnik gotowy.</p>
          <div
            ref="textRegion"
            class="max-h-60 overflow-y-auto whitespace-pre-line rounded-field bg-sunken p-3 text-sm text-ink"
            tabindex="0"
            role="region"
            aria-label="Przewodnik"
          >{{ content.text }}</div>
        </template>
        <q-btn
          v-if="notice?.action"
          flat
          no-caps
          class="min-h-11 px-2"
          color="primary"
          :label="notice.action.label"
          @click="acted = true; emit('refresh')"
        />
      </div>

      <div class="px-4 pt-1">
        <q-btn
          :disable="!button.enabled"
          unelevated
          no-caps
          color="primary"
          class="min-h-11 w-full px-2"
          :label="button.label"
          @click="acted = true; emit('generate')"
        />
      </div>
    </q-card>
  </q-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { QDialog, QCard, QBtn, QSpinner } from 'quasar'
import AppNotice from '@/components/ui/AppNotice.vue'
import type { ContentState } from '@/composables/useContentGeneration'
import { CATEGORY_LABELS, CATEGORY_SLUGS, type CategorySlug } from '@/composables/useCategorySelection'
import type { NearbyItem } from '@/lib/nearbyApi'
import {
  contentNotice,
  contentProgressText,
  formatDistance,
  generateButton,
  poiContentMessage,
} from '@/lib/poiDetail'

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
  content: ContentState
}>()
const emit = defineEmits<{ close: []; generate: []; refresh: [] }>()

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

const statusMessage = computed(() =>
  props.poi === null
    ? null
    : poiContentMessage(props.poi.unlocked, props.accuracyMeters, props.requireProximity),
)

const notice = computed(() => contentNotice(props.content, props.poi?.unlocked === true))
const progressText = computed(() => contentProgressText(props.content))
const button = computed(() => generateButton(props.content))

const contentArea = ref<HTMLElement | null>(null)
const textRegion = ref<HTMLElement | null>(null)

// Focus and the completion message follow an action the user took in this sheet and nothing else:
// the same content arriving from cache on a plain open leaves both alone, and QDialog manages the
// focus of an opening sheet. The flag dies with the POI, so an action never carries over to the
// next one or to a reopened sheet.
const acted = ref(false)

watch(
  () => props.poi?.id,
  () => {
    acted.value = false
  },
)

// A button that disables itself on click, or a retry that leaves the DOM, drops the focus on
// <body>, so the keyboard user has to start over from the top of the page. It moves to the
// content area, which holds whatever the action produces; the finished text takes it directly.
function focusIsLost(): boolean {
  const active = document.activeElement
  return active === null || active === document.body || active.matches(':disabled')
}

watch(
  () => props.content.kind,
  async (kind) => {
    if (!acted.value) return
    await nextTick()
    if (kind === 'completed') textRegion.value?.focus()
    else if (focusIsLost()) contentArea.value?.focus()
  },
  { flush: 'post' },
)
</script>
