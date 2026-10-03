import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function setCsrfCookie(value: string): void {
  document.cookie = `csrf=${value}`
}

function clearCsrfCookie(): void {
  document.cookie = 'csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
}

function response(status: number, body: unknown = {}): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

/** Authorization header from a recorded fetch call's init (headers may be a Headers instance). */
function authHeaderOf(call: unknown[]): string | null {
  const init = (call[1] ?? {}) as RequestInit
  return new Headers(init.headers ?? {}).get('Authorization')
}

beforeEach(() => {
  vi.resetModules()
})

afterEach(() => {
  clearCsrfCookie()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('apiFetch', () => {
  it('attaches the Bearer token to same-origin /api/** requests', async () => {
    const store = await import('@/lib/authStore')
    const { apiFetch } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-x')
    const fetchMock = vi.fn().mockResolvedValue(response(200))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/api/spatial/locations/nearby?lat=1')

    expect(authHeaderOf(fetchMock.mock.calls[0])).toBe('Bearer jwt-x')
  })

  it('never attaches the Bearer token to a third-party URL', async () => {
    const store = await import('@/lib/authStore')
    const { apiFetch } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-x')
    const fetchMock = vi.fn().mockResolvedValue(response(200))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('https://tiles.example.com/tiles/0/0/0.png')

    expect(authHeaderOf(fetchMock.mock.calls[0])).toBeNull()
  })

  it('on 401 refreshes once and retries the original request with the new token', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const { apiFetch } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-old')

    const fetchMock = vi.fn(async (url: unknown) => {
      const u = String(url)
      if (u.includes('/api/auth/refresh')) return response(200, { accessToken: 'jwt-new' })
      return store.getAccessToken() === 'jwt-new' ? response(200, { ok: true }) : response(401)
    })
    vi.stubGlobal('fetch', fetchMock)

    const res = await apiFetch('/api/spatial/foo')

    expect(res.status).toBe(200)
    expect(store.getAccessToken()).toBe('jwt-new')
    const fooCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/foo'))
    expect(fooCalls).toHaveLength(2)
    expect(authHeaderOf(fooCalls[1])).toBe('Bearer jwt-new')
  })

  it('concurrent 401s share ONE refresh, then each retries', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const { apiFetch } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-old')

    let refreshCalls = 0
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const fetchMock = vi.fn(async (url: unknown) => {
      const u = String(url)
      if (u.includes('/api/auth/refresh')) {
        refreshCalls += 1
        await gate
        return response(200, { accessToken: 'jwt-new' })
      }
      return store.getAccessToken() === 'jwt-new' ? response(200) : response(401)
    })
    vi.stubGlobal('fetch', fetchMock)

    const inFlight = Array.from({ length: 5 }, () => apiFetch('/api/spatial/foo'))
    await new Promise((r) => setTimeout(r, 0))
    release()
    const results = await Promise.all(inFlight)

    expect(refreshCalls).toBe(1)
    results.forEach((r) => expect(r.status).toBe(200))
  })

  it('a failed refresh clears the session, signals logged-out, and does not loop', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const { apiFetch } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-old')
    const { isAuthenticated } = store.useAuthStore()

    let refreshCalls = 0
    const fetchMock = vi.fn(async (url: unknown) => {
      if (String(url).includes('/api/auth/refresh')) {
        refreshCalls += 1
        return response(401)
      }
      return response(401)
    })
    vi.stubGlobal('fetch', fetchMock)

    const res = await apiFetch('/api/spatial/foo')

    expect(res.status).toBe(401)
    expect(refreshCalls).toBe(1)
    expect(store.getAccessToken()).toBeNull()
    expect(isAuthenticated.value).toBe(false)
    const fooCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/foo'))
    expect(fooCalls).toHaveLength(1)
  })
})
