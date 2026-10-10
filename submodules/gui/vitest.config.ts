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
  },
})
