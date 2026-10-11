<template>
  <!-- `round` sizes an icon button off its font, which lands at 42px - just under the frozen 44px
       floor for a touch target; min-h/min-w put it back without changing the glyph. -->
  <q-btn
    flat
    round
    class="min-h-11 min-w-11"
    :icon="symRoundedInfo"
    aria-label="Informacje o źródle danych"
  >
    <q-menu class="rounded-card bg-surface shadow-card">
      <p class="max-w-xs p-4 text-sm text-ink">
        Dane o punktach zainteresowania (POI) pochodzą z OpenStreetMap i są udostępniane na
        licencji ODbL: © OpenStreetMap contributors.
      </p>
    </q-menu>
  </q-btn>
</template>

<script setup lang="ts">
import { QBtn, QMenu } from 'quasar'
import { symRoundedInfo } from '@quasar/extras/material-symbols-rounded'
</script>
