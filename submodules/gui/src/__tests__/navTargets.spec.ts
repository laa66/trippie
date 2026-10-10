import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '@/router'

const views = import.meta.glob<string>('/src/views/*.vue', { query: '?raw', import: 'default', eager: true })

// View specs mock vue-router wholesale, so a QBtn `to` never resolves there; this is the only place a typo'd target fails.
describe('navigation targets in views', () => {
  const router = createRouter({ history: createMemoryHistory(), routes })
  const targets = Object.entries(views).flatMap(([file, src]) =>
    [...src.matchAll(/\sto="([^"]+)"/g)].map((m) => [file, m[1]] as const),
  )

  it('finds the known targets', () => {
    expect(targets.length).toBeGreaterThanOrEqual(7)
  })

  it.each(targets)('%s -> %s resolves to a route', (_file, to) => {
    expect(router.resolve(to).matched.length).toBeGreaterThan(0)
  })
})
