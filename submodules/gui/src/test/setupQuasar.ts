import { config } from '@vue/test-utils'
import { Quasar } from 'quasar'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

config.global.plugins = [[Quasar, {}]]

// jsdom has no ResizeObserver and QLayout throws without one. Plain assignment, not vi.stubGlobal:
// specs call vi.unstubAllGlobals() and that would take a stubbed one away mid-file.
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/**
 * The POI layer reads its colours off the document (src/lib/poiLayer.ts), but vitest never
 * processes CSS, so in jsdom those custom properties would resolve to '' and the layer builder
 * would throw on every spec that mounts the map. Rather than keep a second copy of the palette in
 * the test tree - which would drift, and would make any assertion about it a tautology - the
 * declarations are lifted out of the real theme.css and replayed into the document. jsdom reads
 * custom properties off a <style> element fine; it just will not resolve `var()` indirection,
 * which is why theme.css declares these literally. Read off disk rather than imported: vitest
 * stubs every CSS import to an empty module, `?raw` included, and `import.meta.url` is not a file
 * URL under the module runner, so the path comes off the vitest root.
 *
 * ONLY the `:root` block is parsed, and a POI declaration anywhere else is a hard failure. The
 * frozen design system says a dark theme arrives as a `body.body--dark` redefinition of these same
 * properties; a selector-blind scrape would then collect both blocks into one `:root{}` and the
 * LAST one would win, so the whole suite would quietly start asserting the dark palette while the
 * light one - the one that ships - lost its cover entirely. That failure has no symptom, hence the
 * throw. When dark mode lands, this needs a decision (which palette the suite asserts, or both),
 * not a wider regex.
 *
 * Deliberately NOT importing POI_COLOR_PROPERTIES to cross-check the count here: it would drag
 * `@/lib/poiLayer` -> `@/composables/useCategorySelection` into the global setup, and that module
 * reads localStorage and installs an auth watcher at import time, before any spec's mocks exist.
 * The count is cross-checked against the real contract in poiLayer.spec.ts instead.
 */
const POI_DECLARATION = /(--app-poi-[a-z0-9-]+)\s*:\s*([^;]+);/g

function rootBlock(css: string): string {
  const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const open = withoutComments.indexOf('{', withoutComments.indexOf(':root'))
  if (open === -1) throw new Error('setupQuasar: no `:root` block in src/theme.css')

  let depth = 0
  for (let i = open; i < withoutComments.length; i++) {
    if (withoutComments[i] === '{') depth++
    else if (withoutComments[i] === '}' && --depth === 0) return withoutComments.slice(open + 1, i)
  }
  throw new Error('setupQuasar: unterminated `:root` block in src/theme.css')
}

const themeCss = readFileSync(resolve(process.cwd(), 'src/theme.css'), 'utf8')
const inRoot = [...rootBlock(themeCss).matchAll(POI_DECLARATION)]
const everywhere = [...themeCss.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(POI_DECLARATION)]

if (inRoot.length === 0) {
  throw new Error('setupQuasar: src/theme.css declares no --app-poi-* tokens in `:root`')
}
if (everywhere.length !== inRoot.length) {
  throw new Error(
    `setupQuasar: ${everywhere.length - inRoot.length} --app-poi-* declaration(s) live outside ` +
      '`:root` in src/theme.css (a theme block?). Replaying them all into one `:root` would make ' +
      'the suite assert the wrong palette - decide which one the specs should see.',
  )
}

const poiTokens = document.createElement('style')
poiTokens.textContent = `:root{${inRoot.map(([, property, value]) => `${property}:${value.trim()}`).join(';')}}`
document.head.append(poiTokens)
