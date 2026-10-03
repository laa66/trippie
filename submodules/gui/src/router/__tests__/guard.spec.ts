import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

vi.mock('maplibre-gl', () => ({ Map: class {}, Marker: class {} }))
vi.mock('@/lib/settingsApi', () => ({ getSettings: vi.fn(() => new Promise(() => {})), putSettings: vi.fn() }))

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

async function setup(refresh: () => Promise<Response>) {
  vi.resetModules()
  const fetchMock = vi.fn(refresh)
  vi.stubGlobal('fetch', fetchMock)
  const store = await import('@/lib/authStore')
  const client = await import('@/lib/authClient')
  const { authGuard } = await import('@/router/guard')
  const { routes } = await import('@/router/index')
  const router = createRouter({ history: createMemoryHistory(), routes })
  router.beforeEach(authGuard)
  return { router, store, client, fetchMock }
}

// First import of the real route table (MapView, Ionic, settings stack) is slow; pay it here, not on test 1's 5s budget.
beforeAll(async () => {
  await import('@/router/index')
}, 60_000)

beforeEach(() => vi.unstubAllGlobals())

describe('authGuard', () => {
  it('sends an unauthenticated user from /map to /login', async () => {
    const { router } = await setup(async () => jsonResponse(401, {}))
    await router.push('/map')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('lets an unauthenticated user onto the auth screens', async () => {
    const { router } = await setup(async () => jsonResponse(401, {}))
    for (const path of ['/login', '/register', '/verify', '/forgot-password', '/reset-password']) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(path)
    }
  })

  it('sends an authenticated user away from every auth screen to /map', async () => {
    const { router, store, client } = await setup(async () => jsonResponse(401, {}))
    await client.ensureSessionResolved()
    store.setAccessToken('jwt')
    for (const path of ['/login', '/register', '/verify', '/forgot-password', '/reset-password']) {
      await router.push(path)
      expect(router.currentRoute.value.path).toBe('/map')
    }
  })

  it('lets an authenticated user onto /map', async () => {
    const { router, store, client } = await setup(async () => jsonResponse(401, {}))
    await client.ensureSessionResolved()
    store.setAccessToken('jwt')
    await router.push('/map')
    expect(router.currentRoute.value.path).toBe('/map')
  })

  it('protects /settings: anonymous goes to /login, authenticated stays', async () => {
    const anon = await setup(async () => jsonResponse(401, {}))
    await anon.router.push('/settings')
    expect(anon.router.currentRoute.value.path).toBe('/login')

    const authed = await setup(async () => jsonResponse(401, {}))
    await authed.client.ensureSessionResolved()
    authed.store.setAccessToken('jwt')
    await authed.router.push('/settings')
    expect(authed.router.currentRoute.value.path).toBe('/settings')
  })

  it('does not bounce a logged-in user on a cold boot (waits for the boot refresh)', async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    const { router, client } = await setup(async () => {
      await gate
      return jsonResponse(200, { accessToken: 'jwt-boot' })
    })
    void client.ensureSessionResolved()
    const paths: string[] = []
    router.afterEach((to) => {
      paths.push(to.path)
    })
    const nav = router.push('/map')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(paths).toEqual([])
    release()
    await nav
    expect(paths).toEqual(['/map'])
  })

  it('fires the boot refresh only once across many navigations', async () => {
    const { router, fetchMock } = await setup(async () => jsonResponse(401, {}))
    await router.push('/map')
    await router.push('/register')
    await router.push('/map')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
