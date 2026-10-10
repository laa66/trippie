<template>
  <span class="inline-flex items-center gap-2.5" :class="size === 'lg' ? 'text-2xl' : 'text-lg'">
    <span
      class="grid place-items-center rounded-field bg-primary text-ink-inverse"
      :class="size === 'lg' ? 'size-10' : 'size-8'"
      aria-hidden="true"
    >
      <svg :width="size === 'lg' ? 24 : 19" :height="size === 'lg' ? 24 : 19" viewBox="0 0 24 24">
        <path
          d="M12 2.5c-3.87 0-7 3.02-7 6.75 0 4.98 7 12.25 7 12.25s7-7.27 7-12.25c0-3.73-3.13-6.75-7-6.75Zm0 9.4a2.65 2.65 0 1 1 0-5.3 2.65 2.65 0 0 1 0 5.3Z"
          fill="currentColor"
          fill-rule="evenodd"
        />
      </svg>
    </span>
    <span class="font-semibold tracking-tight text-ink">trippie</span>
  </span>
</template>

<script setup lang="ts">
// Typographic lockup, not an asset (user's call in M2.5-06): wordmark plus one inline glyph, so
// swapping in a real logo later touches this file only.
withDefaults(defineProps<{ size?: 'md' | 'lg' }>(), { size: 'md' })
</script>
