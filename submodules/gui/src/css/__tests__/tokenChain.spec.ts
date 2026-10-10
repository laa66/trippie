/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// Pins the P4 token chain on the SOURCES, not on a build: quasar.variables.scss (hex) -> Quasar's
// --q-* -> theme.css @theme. Its failure is silent: Quasar's prebuilt dist/quasar.css also emits
// --q-primary (baked #1976D2), so if the sass import or `sassVariables` is lost, every
// `var(--q-*)` still resolves and the whole palette quietly reverts to Quasar's defaults, with
// vue-tsc, vite build and vitest (which does not process CSS) all green.
const ROLE_TO_SASS: Record<string, string> = {
  primary: 'primary',
  secondary: 'secondary',
  accent: 'accent',
  positive: 'positive',
  negative: 'negative',
  warning: 'warning',
  dark: 'dark',
}

// Read via fs, not `?raw`: vitest runs CSS/SCSS imports through its CSS pipeline and returns ''.
const read = (rel: string): string => readFileSync(resolve(__dirname, rel), 'utf8')
const quasarScss = read('../quasar.scss')
const variablesScss = read('../quasar.variables.scss')
const themeCss = read('../../theme.css')
const viteConfig = read('../../../vite.config.ts')

describe('token chain (P4)', () => {
  it('quasar.scss imports Quasar sass, not the prebuilt css', () => {
    expect(quasarScss).toContain("@import 'quasar/src/css/index.sass'")
    expect(quasarScss).not.toContain('quasar/dist/quasar.css')
  })

  it('vite.config.ts injects the variables file via an absolute sassVariables path', () => {
    expect(viteConfig).toContain('sassVariables: resolve(')
  })

  it('every --color-* in @theme that points at --q-<role> has a sass variable behind it', () => {
    const refs = [...themeCss.matchAll(/--color-[\w-]+:\s*var\(--q-([\w-]+)\)/g)].map((m) => m[1])
    expect(refs.length).toBeGreaterThanOrEqual(7)
    const vars = variablesScss
    for (const role of refs) {
      const sass = ROLE_TO_SASS[role]
      expect(sass, `unknown Quasar role --q-${role}`).toBeDefined()
      expect(vars).toMatch(new RegExp(`^\\$${sass}:\\s*#[0-9a-fA-F]{3,8};`, 'm'))
    }
  })
})
