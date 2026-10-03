import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function res(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

beforeEach(() => vi.resetModules())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('settingsApi', () => {
  it('GET goes through apiFetch with the Bearer and no user id', async () => {
    const store = await import('@/lib/authStore')
    const api = await import('@/lib/settingsApi')
    store.setAccessToken('jwt-s')
    const fetchMock = vi.fn().mockResolvedValue(res(200, { defaultContentMode: 'AUDIO', selectedCategories: ['museums'] }))
    vi.stubGlobal('fetch', fetchMock)

    const settings = await api.getSettings()

    expect(settings).toEqual({ defaultContentMode: 'AUDIO', selectedCategories: ['museums'] })
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/auth/settings')
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer jwt-s')
    expect(String(url)).not.toMatch(/user/i)
  })

  it('PUT sends the body shape with the Bearer and returns the server response', async () => {
    const store = await import('@/lib/authStore')
    const api = await import('@/lib/settingsApi')
    store.setAccessToken('jwt-s')
    const fetchMock = vi.fn().mockResolvedValue(res(200, { defaultContentMode: 'TEXT', selectedCategories: [] }))
    vi.stubGlobal('fetch', fetchMock)

    const out = await api.putSettings({ defaultContentMode: 'BOTH', selectedCategories: ['museums'] })

    expect(out).toEqual({ defaultContentMode: 'TEXT', selectedCategories: [] })
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({ defaultContentMode: 'BOTH', selectedCategories: ['museums'] })
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer jwt-s')
  })

  it('PUT 400 (unknown slug / enum) throws AuthApiError with status and detail', async () => {
    const api = await import('@/lib/settingsApi')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res(400, { detail: 'unknown category' })))
    await expect(api.putSettings({ defaultContentMode: 'BOTH', selectedCategories: ['nope'] })).rejects.toMatchObject({
      status: 400,
      detail: 'unknown category',
    })
  })

  it('rejects a malformed 200 body (unknown mode)', async () => {
    const api = await import('@/lib/settingsApi')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res(200, { defaultContentMode: 'LOUD', selectedCategories: [] })))
    await expect(api.getSettings()).rejects.toThrow(/Malformed/)
  })
})
