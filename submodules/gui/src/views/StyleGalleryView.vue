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
const brand = [
  { token: 'primary', note: 'action, carries white text (5.13:1)', class: 'bg-primary text-ink-inverse' },
  { token: 'secondary', note: 'decorative accent only, never text', class: 'bg-secondary text-dark' },
  { token: 'tint-brand', note: 'informational surface', class: 'bg-tint-brand text-primary' },
  { token: 'danger', note: 'error text (5.17:1 on white)', class: 'bg-tint-danger text-danger' },
  { token: 'warning', note: 'warning text (6.38:1 on white)', class: 'bg-tint-warning text-warning' },
  { token: 'success', note: 'confirmation', class: 'bg-surface text-success' },
]
</script>
