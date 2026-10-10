<template>
  <q-layout view="hHh lpR fFf">
    <q-page-container>
      <!-- pb-10 not py-10: the keyboard shrinks the viewport on mobile, and a centred column with
           equal padding jumps when it opens. Content sits slightly above centre instead. -->
      <q-page class="flex flex-col items-center justify-center px-4 pt-8 pb-10">
        <div class="w-full max-w-sm">
          <div class="mb-6 flex justify-center">
            <app-logo size="lg" />
          </div>
          <div class="rounded-card bg-surface p-6 shadow-card">
            <header class="mb-6 space-y-1 text-center">
              <h1 data-screen-title class="text-xl font-semibold tracking-tight text-ink">{{ title }}</h1>
              <p v-if="subtitle" class="text-sm text-ink-muted">{{ subtitle }}</p>
            </header>
            <slot />
          </div>
          <footer v-if="$slots.footer" class="mt-5 flex flex-col items-center gap-1">
            <slot name="footer" />
          </footer>
        </div>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { QLayout, QPageContainer, QPage } from 'quasar'
import AppLogo from './AppLogo.vue'

// Shell for the five auth screens: the brand lockup sits above the card, the card carries the
// screen title and the form.
// The title carries `data-screen-title` so the copy stays pinned by a test without the test caring
// which chrome renders it (it used to live in a QToolbar).
defineProps<{ title: string; subtitle?: string }>()
</script>
