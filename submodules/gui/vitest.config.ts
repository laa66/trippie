/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { quasar } from '@quasar/vite-plugin'
import { resolve } from 'node:path'

export default defineConfig({
  plugins: [vue(), quasar({ sassVariables: resolve(__dirname, 'src/css/quasar.variables.scss') })],
  resolve: {
    alias: {
      '@': resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setupQuasar.ts'],
    include: ['src/**/*.spec.ts'],
    // 15s, not the 5s default. `main.boot.spec.ts` imports `@/main` from inside its `it()`, and
    // `main.ts` pulls `css/quasar.scss` with it, so a full Quasar sass compile is billed to that
    // one test: 340ms idle, past 5s whenever the machine is busy. Reproduced under load; it is a
    // budget problem, not a hanging test.
    testTimeout: 15_000,
  },
})
