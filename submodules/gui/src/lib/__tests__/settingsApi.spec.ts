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

  // The session controller in useCategorySelection: a logout must cancel whatever it started. The
  // caller is aborted while the calls are IN FLIGHT -- aborting after they settled would only
  // observe a listener that outlived its call, which is the leak, not the feature.
  it('the caller signal aborts an in-flight GET and PUT (session cancellation)', async () => {
    const api = await import('@/lib/settingsApi')
    const signals: AbortSignal[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init?: RequestInit) => {
        const signal = init!.signal!
        signals.push(signal)
        return new Promise<Response>((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)))
      }),
    )
    const caller = new AbortController()

    const get = api.getSettings(caller.signal).catch((e: unknown) => e)
    const put = api.putSettings({ defaultContentMode: 'TEXT', selectedCategories: [] }, caller.signal).catch((e: unknown) => e)
    await Promise.resolve()
    expect(signals).toHaveLength(2)
    expect(signals.every((s) => !s.aborted)).toBe(true)

    caller.abort(new DOMException('session over', 'AbortError'))

    expect(signals.every((s) => s.aborted)).toBe(true)
    expect(await get).toMatchObject({ name: 'AbortError', message: 'session over' })
    expect(await put).toMatchObject({ name: 'AbortError', message: 'session over' })
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

  /** Headers arrive, the body never streams: json() settles only when the signal aborts. */
  function hungBodyFetch(signals: AbortSignal[]): ReturnType<typeof vi.fn> {
    return vi.fn((_url: string, init?: RequestInit) => {
      const signal = init!.signal!
      signals.push(signal)
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => new Promise((_res, rej) => signal.addEventListener('abort', () => rej(signal.reason))),
      } as unknown as Response)
    })
  }

  // Regression, measured: with the budget ending at the Response and the caller reading the body
  // after it, this hung forever (UNSETTLED at 600 s) -- the body-read variant of M2-17 MEDIUM-1,
  // where `hydrating` stays true, canWrite() refuses the write AND skips the retry, so the filter
  // and the content mode are dead until F5.
  it('a 200 whose body never streams rejects with a TimeoutError at 10 s instead of hanging', async () => {
    vi.useFakeTimers()
    try {
      const api = await import('@/lib/settingsApi')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungBodyFetch(signals))

      const outcome = api.getSettings().catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(9_999)
      expect(signals[0].aborted).toBe(false)

      await vi.advanceTimersByTimeAsync(1)

      expect(await outcome).toMatchObject({ name: 'TimeoutError' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('the caller signal aborts a hung BODY read, not only a hung fetch', async () => {
    const api = await import('@/lib/settingsApi')
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', hungBodyFetch(signals))
    const caller = new AbortController()

    const get = api.getSettings(caller.signal).catch((e: unknown) => e)
    await Promise.resolve()
    await Promise.resolve()
    expect(signals[0].aborted).toBe(false)

    caller.abort(new DOMException('session over', 'AbortError'))

    expect(signals[0].aborted).toBe(true)
    expect(await get).toMatchObject({ name: 'AbortError', message: 'session over' })
  })
})
