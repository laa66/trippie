<template>
  <q-layout view="hHh lpR fFf">
    <q-page-container>
      <q-page class="mx-auto w-full max-w-md space-y-8 px-4 py-8">
        <header class="space-y-2">
          <app-logo size="lg" />
          <h1 class="text-xl font-semibold tracking-tight text-ink">Design system</h1>
          <p class="text-sm text-ink-muted">
            Dev-only. Every value here comes from a token; nothing on this page is styled ad hoc.
          </p>
        </header>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Brand</h2>
          <div class="grid grid-cols-2 gap-2">
            <div
              v-for="s in brand"
              :key="s.token"
              class="rounded-field border border-line p-3 text-xs"
              :class="s.class"
            >
              <div class="font-semibold">{{ s.token }}</div>
              <div class="opacity-80">{{ s.note }}</div>
            </div>
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Surfaces &amp; ink</h2>
          <div class="rounded-card bg-surface p-4 shadow-card">
            <p class="text-ink">Ink on surface — body copy, 16px.</p>
            <p class="text-sm text-ink-muted">Muted ink — secondary copy, 14px.</p>
            <div class="mt-3 rounded-field bg-sunken p-3 text-sm text-ink">Sunken surface.</div>
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Type scale</h2>
          <div class="space-y-1">
            <p class="text-2xl font-semibold tracking-tight text-ink">Display 24 / 600</p>
            <p class="text-xl font-semibold tracking-tight text-ink">Title 20 / 600</p>
            <p class="text-base text-ink">Body 16 / 400 — the form baseline (16px stops iOS zoom).</p>
            <p class="text-sm text-ink-muted">Caption 14 / 400</p>
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Buttons</h2>
          <div class="space-y-2">
            <q-btn class="w-full" color="primary" unelevated no-caps size="lg">Akcja główna</q-btn>
            <q-btn class="w-full" color="primary" outline no-caps size="lg">Akcja wtórna</q-btn>
            <q-btn class="w-full" color="primary" flat no-caps>Akcja tekstowa</q-btn>
            <q-btn class="w-full" color="primary" unelevated no-caps size="lg" loading>Zajęty</q-btn>
            <q-btn class="w-full" color="primary" unelevated no-caps size="lg" disable>Nieaktywny</q-btn>
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Fields</h2>
          <div class="space-y-4">
            <q-input outlined color="primary" label="Puste" model-value="" />
            <q-input outlined color="primary" label="Wypełnione" model-value="ja@example.com" />
            <q-input outlined color="primary" label="Błąd" model-value="zly-adres" error error-message="Podaj poprawny adres e-mail." />
            <q-input outlined color="primary" label="Nieaktywne" model-value="—" disable />
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Notices</h2>
          <div class="space-y-2">
            <app-notice variant="danger" role="alert">Nieprawidłowy e-mail lub hasło.</app-notice>
            <app-notice variant="warning" role="status">
              Poprzednie wylogowanie nie zostało potwierdzone przez serwer — sesja może być nadal aktywna.
            </app-notice>
            <app-notice variant="info" role="status">Jeśli konto istnieje, wysłaliśmy na ten adres kod.</app-notice>
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Settings card</h2>
          <!-- App-screen pattern (SettingsView): a card per section, header via q-item-label,
               brand-coloured controls. The controls need no display fix here - P15 is handled once
               in theme.css, in the utilities layer. -->
          <q-list class="rounded-card bg-surface pb-2 shadow-card">
            <q-item-label header class="text-ink-muted">Grupa opcji</q-item-label>
            <q-option-group v-model="mode" class="px-2" type="radio" color="primary" :options="modes" />
          </q-list>
          <q-list class="rounded-card bg-surface pb-2 shadow-card">
            <q-item-label header class="text-ink-muted">Przełączniki</q-item-label>
            <q-item v-for="t in toggles" :key="t.label">
              <q-item-section>
                <q-toggle v-model="t.on" class="w-full flex-nowrap justify-between" left-label color="primary" :label="t.label" />
              </q-item-section>
            </q-item>
          </q-list>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">POI map palette</h2>
          <!-- The one token family that never becomes a CSS class: MapLibre takes paint colours as
               style values, so src/lib/poiLayer.ts reads these off the document. They are listed
               here because otherwise nothing in the app shows them side by side. -->
          <div class="grid grid-cols-2 gap-2">
            <div v-for="c in poiTokens" :key="c.token" class="flex items-center gap-2.5 text-xs text-ink">
              <span class="poi-dot shrink-0" :style="{ background: `var(--app-poi-${c.token})` }" />
              <span class="min-w-0">
                <span class="block font-semibold">{{ c.label }}</span>
                <span class="block text-ink-muted">--app-poi-{{ c.token }}</span>
              </span>
            </div>
          </div>
          <p class="text-xs text-ink-muted">
            Ring = --app-poi-stroke, the same 2px white the map draws. Every dot clears 3:1 against
            it; the closest pair is dE2000 14.4 and none comes nearer than 23.9 to the brand green
            the user-location marker uses.
          </p>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Map chrome</h2>
          <!-- A notice floating over the map (MapView's geolocation banner) is the same AppNotice
               the forms use, plus shadow-raised: the tint is opaque, so it stays readable over
               tiles. The header, FAB and the MapLibre attribution pill are composed from the same
               tokens inside MapView.vue and cannot be reproduced here without duplicating them. -->
          <div class="rounded-card bg-sunken p-4">
            <app-notice variant="warning" role="status" class="shadow-raised">
              Brak zgody na lokalizację — pokazuję centrum Wrocławia.
            </app-notice>
          </div>
        </section>

        <section class="space-y-3">
          <h2 class="text-sm font-semibold uppercase tracking-wide text-ink-muted">Shape &amp; elevation</h2>
          <div class="grid grid-cols-3 gap-2 text-center text-xs text-ink-muted">
            <div class="rounded-field border border-line p-4">radius-field</div>
            <div class="rounded-card border border-line p-4">radius-card</div>
            <div class="rounded-pill border border-line p-4">radius-pill</div>
            <div class="rounded-card bg-surface p-4 shadow-card">shadow-card</div>
            <div class="rounded-card bg-surface p-4 shadow-raised">shadow-raised</div>
          </div>
        </section>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import {
  QLayout,
  QPageContainer,
  QPage,
  QInput,
  QBtn,
  QList,
  QItem,
  QItemSection,
  QItemLabel,
  QOptionGroup,
  QToggle,
} from 'quasar'
import AppLogo from '@/components/ui/AppLogo.vue'
import AppNotice from '@/components/ui/AppNotice.vue'
import { CATEGORY_LABELS, CATEGORY_SLUGS } from '@/composables/useCategorySelection'

// The canvas for M2.5-06: states that are awkward to reach on a live screen (a field in error, a
// button mid-request) sit here side by side. Dev-only route, dynamically imported, so it never
// reaches the production bundle.
const mode = ref('AUDIO')
const modes = [
  { value: 'TEXT', label: 'Tekst' },
  { value: 'AUDIO', label: 'Audio' },
]
const toggles = ref([
  { label: 'Włączony', on: true },
  { label: 'Wyłączony', on: false },
])
// Derived from the slug contract, not retyped: the labels and the token names are the same list
// the map uses, so adding a category cannot leave this gallery showing eight of nine.
const poiTokens = [
  ...CATEGORY_SLUGS.map((slug) => ({ token: slug.replace('_', '-'), label: CATEGORY_LABELS[slug] })),
  { token: 'unknown', label: 'Fallback (unreachable)' },
]
const brand = [
  { token: 'primary', note: 'action, carries white text (5.13:1)', class: 'bg-primary text-ink-inverse' },
  { token: 'secondary', note: 'decorative accent only, never text', class: 'bg-secondary text-dark' },
  { token: 'tint-brand', note: 'informational surface', class: 'bg-tint-brand text-primary' },
  { token: 'danger', note: 'error text on its tint (4.92:1)', class: 'bg-tint-danger text-danger' },
  { token: 'warning', note: 'warning text on its tint (5.95:1)', class: 'bg-tint-warning text-warning' },
  { token: 'success', note: 'confirmation', class: 'bg-surface text-success' },
]
</script>

<style scoped>
/* The POI tokens are the only colours in the system with no utility class; the swatch reads the
   one it is given straight off the custom property, so there is no literal and no second list. */
.poi-dot {
  width: 20px;
  height: 20px;
  border: 2px solid var(--app-poi-stroke);
  border-radius: var(--radius-pill);
  box-shadow: var(--app-shadow-card);
}
</style>
