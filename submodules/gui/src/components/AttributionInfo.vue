<template>
  <q-btn flat round :icon="symRoundedInfo" aria-label="Informacje o źródle danych">
    <q-menu>
      <p class="max-w-xs p-4">
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
