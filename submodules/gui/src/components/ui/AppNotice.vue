<template>
  <!-- flex-nowrap is load-bearing (P11): Quasar's own `.flex` adds `flex-wrap: wrap`, which drops
       the icon onto its own line as soon as the message runs past one line. -->
  <div class="flex flex-nowrap items-start gap-2.5 rounded-field p-3 text-sm" :class="tone.box" :role="role">
    <!-- The icon box is exactly one line tall (1.5em = the text's line-height), so the glyph is
         optically centred on the FIRST line whether the message wraps or not. Aligning the icon to
         the top of the container instead leaves it sitting a hair above the text. -->
    <span class="flex h-[1.5em] shrink-0 items-center">
      <q-icon :name="tone.icon" size="20px" :class="tone.ink" aria-hidden="true" />
    </span>
    <p class="min-w-0"><slot /></p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { QIcon } from 'quasar'
import { symRoundedError, symRoundedWarning, symRoundedInfo } from '@quasar/extras/material-symbols-rounded'

// Every in-page message goes through here, for one reason: a message's colour must never be its
// only carrier, and the colour it does use has to pass AA. The M2-19 logout notice used to be a
// bare `#ffc409` label on white - 1.60:1, effectively unreadable. Here the hue lives in a tint
// behind dark text, so contrast comes from the ink, not the brand hue.
// `role` is required, not optional: the whole point of funnelling every message through here is
// that none of them can be announced-less, and an optional prop would let the next one slip out
// silently. vue-tsc now rejects a notice without it.
const props = defineProps<{ variant: 'danger' | 'warning' | 'info'; role: 'alert' | 'status' }>()

const tones = {
  danger: { box: 'bg-tint-danger text-danger', icon: symRoundedError, ink: 'text-danger' },
  warning: { box: 'bg-tint-warning text-warning', icon: symRoundedWarning, ink: 'text-warning' },
  info: { box: 'bg-tint-brand text-primary', icon: symRoundedInfo, ink: 'text-primary' },
} as const

const tone = computed(() => tones[props.variant])
</script>
