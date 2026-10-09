import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { h } from 'vue'

/**
 * Boot wiring of `main.ts` (M2-19 HIGH-2, Light's review). Nothing in the repo imports `main.ts`, so
 * every assertion about the boot sequence lived only in a doc comment: deleting
 * `void resumePendingLogout()` left all other tests green while the whole replay feature went dead.
 * That is the same failure shape that earned criterion 20 — a green sheet over a feature that cannot
 * be reached in production — one layer up from the gateway.
 *
 * WHY THE BOOT GRAPH IS MOCKED (not an optimisation — a flakiness fix): `await import('@/main')`
 * pulls the real composition root, and through `./router` every view, including `MapView.vue`'s
 * value import of `maplibre-gl` (~1 MB), plus `@ionic/vue` and `ionicons`. Transpiling and executing
 * that graph cost ~1.7 s of the 5 s default budget on an idle machine and blew past it under
 * parallel Gradle/Testcontainers load. Mocking `App.vue`, the router and `IonicVue` removes the
 * entire cost while touching nothing this spec asserts: the subject is which auth calls `main.ts`
 * makes and in what order, and that is unaffected by what gets mounted. Raising the timeout instead
 * would have kept a multi-second spec that only ever asserts two call orders.
 */

vi.mock('@/lib/authClient', () => ({
  resumePendingLogout: vi.fn(() => Promise.resolve()),
  ensureSessionResolved: vi.fn(() => Promise.resolve(false)),
}))

// Minimal stand-ins: a component with a render function (no template — the runtime-only Vue build
// has no compiler) and two no-op Vue plugins.
vi.mock('@/App.vue', () => ({ default: { name: 'AppStub', render: () => h('div') } }))
vi.mock('@/router', () => ({ default: { install: () => undefined } }))
vi.mock('@ionic/vue', () => ({ IonicVue: { install: () => undefined } }))

beforeEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  document.body.innerHTML = '<div id="app"></div>'
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('main.ts boot', () => {
  it('replays a pending logout and resolves the session, replay FIRST', async () => {
    const { resumePendingLogout, ensureSessionResolved } = await import('@/lib/authClient')

    await import('@/main')

    expect(resumePendingLogout).toHaveBeenCalledTimes(1)
    expect(ensureSessionResolved).toHaveBeenCalledTimes(1)

    // Order is load-bearing, not cosmetic: resumePendingLogout reads the marker synchronously and
    // ensureSessionResolved must still see it, or the boot would fire a /refresh and mint a live
    // access token on a device the user believes they signed out of (criterion 7).
    const replayOrder = vi.mocked(resumePendingLogout).mock.invocationCallOrder[0]
    const resolveOrder = vi.mocked(ensureSessionResolved).mock.invocationCallOrder[0]
    expect(replayOrder).toBeLessThan(resolveOrder)
  })
})
