import { describe, expect, it } from 'vitest'

const sources = import.meta.glob<string>('/src/**/*.vue', { query: '?raw', import: 'default', eager: true })
const files = Object.keys(sources)

describe('no raw HTML injection', () => {
  it('finds the .vue files it is guarding', () => {
    expect(files.length).toBeGreaterThan(5)
  })

  it.each(files)('%s uses neither v-html nor innerHTML', (file) => {
    expect(sources[file]).not.toMatch(/v-html|innerHTML/)
  })

  // The generated guide text is model output, i.e. untrusted input. The scan above would pass if the
  // sheet stopped rendering it at all, so pin that it is the one that renders it, and as text.
  it('renders the generated guide text by interpolation in PoiDetailSheet', () => {
    const sheet = sources['/src/components/PoiDetailSheet.vue']
    expect(sheet).toMatch(/\{\{\s*content\.text\s*\}\}/)
    expect(sheet).not.toMatch(/v-text|v-html|innerHTML|\bdomProps\b/)
  })
})
