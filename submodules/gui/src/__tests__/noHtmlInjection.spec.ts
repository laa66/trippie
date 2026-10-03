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
})
