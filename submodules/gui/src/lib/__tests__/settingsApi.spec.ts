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

  it('passes a signal that aborts when the caller aborts', async () => {
    const api = await import('@/lib/settingsApi')
    const fetchMock = vi.fn().mockResolvedValue(res(200, { defaultContentMode: 'TEXT', selectedCategories: [] }))
    vi.stubGlobal('fetch', fetchMock)
    const caller = new AbortController()

    await api.getSettings(caller.signal)
    await api.putSettings({ defaultContentMode: 'TEXT', selectedCategories: [] }, caller.signal)

    const signals = fetchMock.mock.calls.map((c) => (c[1] as RequestInit).signal as AbortSignal)
    expect(signals.every((s) => !s.aborted)).toBe(true)
    caller.abort()
    expect(signals.every((s) => s.aborted)).toBe(true)
  })

  it('aborts a request that never settles after 10 s, with or without a caller signal', async () => {
    vi.useFakeTimers()
    try {
      const api = await import('@/lib/settingsApi')
      const fetchMock = vi.fn((_url: string, init?: RequestInit) => {
        return new Promise<Response>((_r, reject) => {
          init!.signal!.addEventListener('abort', () => reject(init!.signal!.reason))
        })
      })
      vi.stubGlobal('fetch', fetchMock)

      const get = api.getSettings().catch((e: unknown) => e)
      const put = api.putSettings({ defaultContentMode: 'TEXT', selectedCategories: [] }, new AbortController().signal).catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(9_999)
      expect(fetchMock.mock.calls.every((c) => !(c[1] as RequestInit).signal!.aborted)).toBe(true)
      await vi.advanceTimersByTimeAsync(1)

      expect(await get).toMatchObject({ name: 'TimeoutError' })
      expect(await put).toMatchObject({ name: 'TimeoutError' })
    } finally {
      vi.useRealTimers()
    }
  })
})
