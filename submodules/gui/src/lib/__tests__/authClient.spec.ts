import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

function setCsrfCookie(value: string): void {
  document.cookie = `csrf=${value}`
}

function clearCsrfCookie(): void {
  document.cookie = 'csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
}

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

/** A fetch that never answers on its own: it only rejects when its signal is aborted. */
function hungFetch(signals: AbortSignal[]): ReturnType<typeof vi.fn> {
  return vi.fn((_url: string, init?: RequestInit) => {
    const signal = init?.signal ?? null
    if (signal !== null) signals.push(signal)
    return new Promise<Response>((_resolve, reject) => {
      signal?.addEventListener('abort', () => reject(signal.reason))
    })
  })
}

/** Resolves to the promise's value if it has already settled, else to the marker — so an unsettled promise asserts. */
function settledOr<T>(promise: Promise<T>, marker: string): Promise<T | string> {
  return Promise.race([promise, Promise.resolve(marker)])
}

beforeEach(() => {
  vi.resetModules()
})

afterEach(() => {
  clearCsrfCookie()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('authClient', () => {
  it('login stores the returned access token and POSTs the credentials body', async () => {
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt-login' }))
    vi.stubGlobal('fetch', fetchMock)

    await client.login('user@example.com', 'secret-pw')

    expect(store.getAccessToken()).toBe('jwt-login')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(String(url)).toContain('/api/auth/login')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ email: 'user@example.com', password: 'secret-pw' })
  })

  it('refresh echoes the csrf cookie value in the X-CSRF-Token header', async () => {
    setCsrfCookie('csrf-token-abc')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt' }))
    vi.stubGlobal('fetch', fetchMock)

    await client.refreshAccessToken()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(String(url)).toContain('/api/auth/refresh')
    expect(init.method).toBe('POST')
    expect((init.headers as Record<string, string>)['X-CSRF-Token']).toBe('csrf-token-abc')
  })

  it('boot silent-refresh populates the in-memory token from the cookie session', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'boot-jwt' })))

    const ok = await client.ensureSessionResolved()

    expect(ok).toBe(true)
    expect(store.getAccessToken()).toBe('boot-jwt')
  })

  it('a failed refresh clears the token and flips the logged-out signal', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('stale-jwt')
    const { isAuthenticated } = store.useAuthStore()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401, {})))

    const ok = await client.refreshAccessToken()

    expect(ok).toBe(false)
    expect(store.getAccessToken()).toBeNull()
    expect(isAuthenticated.value).toBe(false)
  })

  it('treats a 200 refresh with no accessToken as a failed refresh (fail-closed boundary)', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('stale-jwt')
    const { isAuthenticated } = store.useAuthStore()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { somethingElse: true })))

    const ok = await client.refreshAccessToken()

    expect(ok).toBe(false)
    expect(store.getAccessToken()).toBeNull()
    expect(isAuthenticated.value).toBe(false)
  })

  it('single-flight: concurrent refresh calls share one in-flight request', async () => {
    setCsrfCookie('csrf-1')
    const client = await import('@/lib/authClient')
    let refreshCalls = 0
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        refreshCalls += 1
        await gate
        return jsonResponse(200, { accessToken: 'jwt-shared' })
      }),
    )

    const calls = [client.refreshAccessToken(), client.refreshAccessToken(), client.refreshAccessToken()]
    await new Promise((r) => setTimeout(r, 0))
    release()
    const results = await Promise.all(calls)

    expect(refreshCalls).toBe(1)
    expect(results).toEqual([true, true, true])
  })

  it('login throws a typed AuthApiError carrying status, type and detail', async () => {
    const client = await import('@/lib/authClient')
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(403, { type: client.EMAIL_NOT_VERIFIED_TYPE, detail: 'verify first', title: 't' }),
      ),
    )
    const err = await client.login('a@b.c', 'pw').catch((e) => e)
    expect(err).toBeInstanceOf(client.AuthApiError)
    expect(err).toMatchObject({ status: 403, type: client.EMAIL_NOT_VERIFIED_TYPE, detail: 'verify first' })
  })

  it('a non-JSON error body still yields an AuthApiError with the status', async () => {
    const client = await import('@/lib/authClient')
    const res = {
      ok: false,
      status: 502,
      json: async () => {
        throw new SyntaxError('bad json')
      },
    } as unknown as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res))
    const err = await client.login('a@b.c', 'pw').catch((e) => e)
    expect(err).toMatchObject({ status: 502, type: null, detail: null })
  })

  it('register, verifyEmail and resendCode POST the documented bodies', async () => {
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, null))
    vi.stubGlobal('fetch', fetchMock)
    await client.register('a@b.c', 'pw12345678')
    await client.verifyEmail('a@b.c', '123456')
    await client.resendCode('a@b.c')
    const calls = fetchMock.mock.calls as [string, RequestInit][]
    expect(calls.map(([u]) => u)).toEqual(['/api/auth/register', '/api/auth/verify', '/api/auth/resend'])
    expect(calls.map(([, i]) => JSON.parse(i.body as string))).toEqual([
      { email: 'a@b.c', password: 'pw12345678' },
      { email: 'a@b.c', code: '123456' },
      { email: 'a@b.c' },
    ])
  })

  it('register surfaces a 409 as AuthApiError', async () => {
    const client = await import('@/lib/authClient')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(409, { detail: 'dup' })))
    await expect(client.register('a@b.c', 'pw12345678')).rejects.toMatchObject({ status: 409 })
  })

  it('logout sends Bearer + X-CSRF-Token and clears the session', async () => {
    setCsrfCookie('csrf-xyz')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(204, null))
    vi.stubGlobal('fetch', fetchMock)

    await client.logout()

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/auth/logout')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('same-origin')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer jwt-live', 'X-CSRF-Token': 'csrf-xyz' })
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  it('logout clears the session even when the server answers non-2xx or the network fails', async () => {
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(403, {})))
    await client.logout()
    expect(store.getAccessToken()).toBeNull()

    store.setAccessToken('jwt-live')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    await client.logout()
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  it('ensureSessionResolved memoizes the boot refresh: one /refresh, same promise forever', async () => {
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt' }))
    vi.stubGlobal('fetch', fetchMock)

    const first = client.ensureSessionResolved()
    await first
    const second = client.ensureSessionResolved()
    await second

    expect(second).toBe(first)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('logout on an expired access token refreshes once and resends with the rotated token and csrf', async () => {
    setCsrfCookie('csrf-old')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-expired')
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/auth/logout' && fetchMock.mock.calls.filter(([u]) => u === url).length === 1) {
        return jsonResponse(401, {})
      }
      if (url === '/api/auth/refresh') {
        setCsrfCookie('csrf-new')
        return jsonResponse(200, { accessToken: 'jwt-fresh' })
      }
      return jsonResponse(204, null)
    })
    vi.stubGlobal('fetch', fetchMock)

    await client.logout()

    const calls = fetchMock.mock.calls as unknown as [string, RequestInit][]
    expect(calls.map(([u]) => u)).toEqual(['/api/auth/logout', '/api/auth/refresh', '/api/auth/logout'])
    expect(calls[2][1].headers).toMatchObject({ Authorization: 'Bearer jwt-fresh', 'X-CSRF-Token': 'csrf-new' })
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  it('a refresh in flight when logout completes cannot resurrect the session, and later refreshes are refused', async () => {
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') {
        await gate
        return jsonResponse(200, { accessToken: 'jwt-straggler' })
      }
      return jsonResponse(204, null)
    })
    vi.stubGlobal('fetch', fetchMock)

    const straggler = client.refreshAccessToken()
    await client.logout()
    release()
    await straggler
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)

    fetchMock.mockClear()
    expect(await client.refreshAccessToken()).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('a normal login after a logout re-opens the session (latch does not wedge)', async () => {
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(204, null)))
    await client.logout()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt-new' })))
    await client.login('a@b.c', 'pw')
    expect(store.getAccessToken()).toBe('jwt-new')
    expect(await client.refreshAccessToken()).toBe(true)
  })

  it.each([
    ['401', async () => jsonResponse(401, {})],
    [
      'network failure',
      async () => {
        throw new TypeError('offline')
      },
    ],
  ])('a stale refresh failing (%s) after logout + login does not wipe the new session', async (_name, failure) => {
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-old')
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') {
        await gate
        return failure()
      }
      if (url === '/api/auth/login') {
        return jsonResponse(200, { accessToken: 'jwt-NEW' })
      }
      return jsonResponse(204, null)
    })
    vi.stubGlobal('fetch', fetchMock)

    const straggler = client.refreshAccessToken()
    await client.logout()
    await client.login('a@b.c', 'pw')
    release()
    expect(await straggler).toBe(false)

    expect(store.getAccessToken()).toBe('jwt-NEW')
    expect(store.useAuthStore().isAuthenticated.value).toBe(true)
  })

  it('a straggler settling does not free the single-flight slot of a newer refresh', async () => {
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-old')
    const releases: (() => void)[] = []
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/api/auth/refresh') {
        await new Promise<void>((r) => releases.push(r))
        return jsonResponse(200, { accessToken: 'jwt-r' })
      }
      if (url === '/api/auth/login') {
        return jsonResponse(200, { accessToken: 'jwt-NEW' })
      }
      return jsonResponse(204, null)
    })
    vi.stubGlobal('fetch', fetchMock)

    const straggler = client.refreshAccessToken()
    await client.logout()
    await client.login('a@b.c', 'pw')
    const newer = client.refreshAccessToken()
    const refreshCalls = () => fetchMock.mock.calls.filter(([u]) => u === '/api/auth/refresh').length
    expect(refreshCalls()).toBe(2)

    releases[0]()
    await straggler

    expect(client.refreshAccessToken()).toBe(newer)
    expect(refreshCalls()).toBe(2)
    releases[1]()
    await newer
  })

  it('a response whose body never streams is aborted at 10 s: the timeout covers the body read, not just the headers', async () => {
    vi.useFakeTimers()
    try {
      const client = await import('@/lib/authClient')
      vi.stubGlobal(
        'fetch',
        vi.fn((_url: string, init?: RequestInit) => {
          const signal = init!.signal!
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => new Promise((_res, rej) => signal.addEventListener('abort', () => rej(signal.reason))),
          } as unknown as Response)
        }),
      )

      const outcome = client.requestPasswordReset('a@b.c').then(
        () => 'settled',
        () => 'settled',
      )
      await vi.advanceTimersByTimeAsync(9_999)
      expect(await settledOr(outcome, 'unsettled')).toBe('unsettled')

      await vi.advanceTimersByTimeAsync(1)

      expect(await settledOr(outcome, 'unsettled')).toBe('settled')
    } finally {
      vi.useRealTimers()
    }
  })

  it('a /refresh that never answers is aborted after 10 s and settles as a failed refresh', async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-1')
      const store = await import('@/lib/authStore')
      const client = await import('@/lib/authClient')
      store.setAccessToken('stale-jwt')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const pending = client.refreshAccessToken()
      await vi.advanceTimersByTimeAsync(9_999)
      expect(await settledOr(pending, 'unsettled')).toBe('unsettled')

      await vi.advanceTimersByTimeAsync(1)

      expect(await settledOr(pending, 'unsettled')).toBe(false)
      expect(signals[0].aborted).toBe(true)
      expect((signals[0].reason as DOMException).name).toBe('TimeoutError')
      expect(store.getAccessToken()).toBeNull()
      expect(store.useAuthStore().isAuthenticated.value).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a timed-out refresh frees the single-flight slot: the next refresh fires a new /refresh', async () => {
    vi.useFakeTimers()
    try {
      const client = await import('@/lib/authClient')
      const fetchMock = hungFetch([])
      vi.stubGlobal('fetch', fetchMock)

      const first = client.refreshAccessToken()
      expect(client.refreshAccessToken()).toBe(first)
      await vi.advanceTimersByTimeAsync(10_000)
      expect(await settledOr(first, 'unsettled')).toBe(false)

      const second = client.refreshAccessToken()
      expect(second).not.toBe(first)
      expect(fetchMock).toHaveBeenCalledTimes(2)
      await vi.advanceTimersByTimeAsync(10_000)
      expect(await second).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a refresh that answers normally leaves no pending timeout timer behind', async () => {
    vi.useFakeTimers()
    try {
      const client = await import('@/lib/authClient')
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt' })))

      expect(await client.refreshAccessToken()).toBe(true)

      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a /logout that never answers is abandoned after 5 s and still drops the local session', async () => {
    vi.useFakeTimers()
    try {
      const store = await import('@/lib/authStore')
      const client = await import('@/lib/authClient')
      store.setAccessToken('jwt-live')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const pending = client.logout()
      await vi.advanceTimersByTimeAsync(4_999)
      expect(await settledOr(pending, 'unsettled')).toBe('unsettled')

      await vi.advanceTimersByTimeAsync(1)

      expect(await settledOr(pending, 'unsettled')).toBeUndefined()
      expect(signals[0].aborted).toBe(true)
      expect(store.getAccessToken()).toBeNull()
      expect(store.useAuthStore().isAuthenticated.value).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('a form POST that never answers rejects with a TimeoutError after 10 s', async () => {
    vi.useFakeTimers()
    try {
      const client = await import('@/lib/authClient')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const pending = client.login('a@b.c', 'pw').then(
        () => 'resolved',
        (e: unknown) => e,
      )
      await vi.advanceTimersByTimeAsync(9_999)
      expect(await settledOr(pending, 'unsettled')).toBe('unsettled')

      await vi.advanceTimersByTimeAsync(1)

      const outcome = await settledOr(pending, 'unsettled')
      expect(outcome).toBeInstanceOf(DOMException)
      expect((outcome as DOMException).name).toBe('TimeoutError')
      expect(signals[0].aborted).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('requestPasswordReset and resetPassword POST the documented bodies', async () => {
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, null))
    vi.stubGlobal('fetch', fetchMock)
    await client.requestPasswordReset('a@b.c')
    await client.resetPassword('a@b.c', '123456', 'new-password')
    const calls = fetchMock.mock.calls as [string, RequestInit][]
    expect(calls.map(([u]) => u)).toEqual(['/api/auth/forgot-password', '/api/auth/reset-password'])
    expect(calls.map(([, i]) => JSON.parse(i.body as string))).toEqual([
      { email: 'a@b.c' },
      { email: 'a@b.c', code: '123456', newPassword: 'new-password' },
    ])
  })

  it('resetPassword surfaces a 400 as AuthApiError with the backend detail', async () => {
    const client = await import('@/lib/authClient')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(400, { detail: 'bad code' })))
    await expect(client.resetPassword('a@b.c', '000000', 'new-password')).rejects.toMatchObject({
      status: 400,
      detail: 'bad code',
    })
  })
})
