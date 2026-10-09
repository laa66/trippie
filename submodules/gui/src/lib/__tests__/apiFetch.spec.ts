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

/** A fetch that never answers on its own: it records its signal and rejects only when aborted. */
function hungFetch(signals: AbortSignal[]): ReturnType<typeof vi.fn> {
  return vi.fn((_url: unknown, init?: RequestInit) => {
    const signal = init!.signal!
    signals.push(signal)
    return new Promise<Response>((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)))
  })
}

/** Headers arrive, the body never streams: json() settles only when the signal aborts. */
function hungBodyFetch(signals: AbortSignal[], status = 200): ReturnType<typeof vi.fn> {
  return vi.fn((_url: unknown, init?: RequestInit) => {
    const signal = init!.signal!
    signals.push(signal)
    return Promise.resolve({
      ok: status >= 200 && status < 300,
      status,
      json: () => new Promise((_res, rej) => signal.addEventListener('abort', () => rej(signal.reason))),
    } as unknown as Response)
  })
}

/** Resolves to the promise's value if it has already settled, else to the marker. */
function settledOr<T>(promise: Promise<T>, marker: string): Promise<T | string> {
  return Promise.race([promise, Promise.resolve(marker)])
}

/** Flushes every pending microtask (real timers only). */
function flushMicrotasks(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
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

describe('apiFetchJson', () => {
  it('attaches the Bearer token to same-origin /api/** requests and returns the parsed body', async () => {
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-x')
    const fetchMock = vi.fn().mockResolvedValue(response(200, { items: [1] }))
    vi.stubGlobal('fetch', fetchMock)

    const body = await apiFetchJson('/api/spatial/locations/nearby?lat=1')

    expect(body).toEqual({ items: [1] })
    expect(authHeaderOf(fetchMock.mock.calls[0])).toBe('Bearer jwt-x')
  })

  it('never attaches the Bearer token to a third-party URL', async () => {
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-x')
    const fetchMock = vi.fn().mockResolvedValue(response(200))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetchJson('https://tiles.example.com/tiles/0/0/0.png')

    expect(authHeaderOf(fetchMock.mock.calls[0])).toBeNull()
  })

  it('on 401 refreshes once and retries the original request with the new token', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-old')

    const fetchMock = vi.fn(async (url: unknown) => {
      const u = String(url)
      if (u.includes('/api/auth/refresh')) return response(200, { accessToken: 'jwt-new' })
      return store.getAccessToken() === 'jwt-new' ? response(200, { ok: true }) : response(401)
    })
    vi.stubGlobal('fetch', fetchMock)

    const body = await apiFetchJson('/api/spatial/foo')

    expect(body).toEqual({ ok: true })
    expect(store.getAccessToken()).toBe('jwt-new')
    const fooCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/foo'))
    expect(fooCalls).toHaveLength(2)
    expect(authHeaderOf(fooCalls[1])).toBe('Bearer jwt-new')
  })

  it('concurrent 401s share ONE refresh, then each retries', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
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
      return store.getAccessToken() === 'jwt-new' ? response(200, { ok: true }) : response(401)
    })
    vi.stubGlobal('fetch', fetchMock)

    const inFlight = Array.from({ length: 5 }, () => apiFetchJson('/api/spatial/foo'))
    await new Promise((r) => setTimeout(r, 0))
    release()
    const results = await Promise.all(inFlight)

    expect(refreshCalls).toBe(1)
    results.forEach((body) => expect(body).toEqual({ ok: true }))
  })

  it('a failed refresh clears the session, signals logged-out, and does not loop', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
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

    const failure = await apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)

    expect(failure).toMatchObject({ status: 401 })
    expect(refreshCalls).toBe(1)
    expect(store.getAccessToken()).toBeNull()
    expect(isAuthenticated.value).toBe(false)
    const fooCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/foo'))
    expect(fooCalls).toHaveLength(1)
  })

  // A foreign server's 401 says nothing about OUR session: refreshing on it would let a third party
  // drive our auth state (and a failed refresh there would clear the session outright).
  it('never refreshes or retries on a 401 from a third-party URL', async () => {
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
    store.setAccessToken('jwt-live')
    const fetchMock = vi.fn().mockResolvedValue(response(401))
    vi.stubGlobal('fetch', fetchMock)

    const failure = await apiFetchJson('https://tiles.example.com/tiles/0/0/0.png').catch((e: unknown) => e)

    expect(failure).toMatchObject({ status: 401 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls.map((c) => String(c[0]))).not.toContain('/api/auth/refresh')
    expect(store.getAccessToken()).toBe('jwt-live')
  })

  // The shape the callers used to build themselves from problemOf(res); settingsApi's 400 handling
  // and useCategorySelection's revert-on-failed-PUT both read it off this.
  it('a READABLE non-2xx still throws the AuthApiError with status, type and detail', async () => {
    const { apiFetchJson } = await import('@/lib/apiFetch')
    const { AuthApiError } = await import('@/lib/authClient')

    for (const status of [400, 500]) {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(response(status, { type: 'urn:trippie:bad', detail: 'unknown category' })),
      )

      const failure = await apiFetchJson('/api/auth/settings').catch((e: unknown) => e)

      expect(failure).toBeInstanceOf(AuthApiError)
      expect(failure).toMatchObject({ status, type: 'urn:trippie:bad', detail: 'unknown category' })
    }
  })

  it('bounds an attempt at 10 s even with no caller signal (nearbyApi rides on this)', async () => {
    vi.useFakeTimers()
    try {
      const { apiFetchJson } = await import('@/lib/apiFetch')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const outcome = apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(9_999)
      expect(signals[0].aborted).toBe(false)

      await vi.advanceTimersByTimeAsync(1)

      expect(signals[0].aborted).toBe(true)
      expect(await outcome).toMatchObject({ name: 'TimeoutError' })
    } finally {
      vi.useRealTimers()
    }
  })

  // The window has to cover the BODY read, not just the headers: returning a Response the caller
  // reads afterwards is what made a hung body stream unbounded (measured: UNSETTLED at 600 s).
  it('bounds the BODY read inside the same window: a 200 whose body never streams rejects at 10 s', async () => {
    vi.useFakeTimers()
    try {
      const { apiFetchJson } = await import('@/lib/apiFetch')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungBodyFetch(signals))

      const outcome = apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(9_999)
      expect(signals[0].aborted).toBe(false)

      await vi.advanceTimersByTimeAsync(1)

      expect(await outcome).toMatchObject({ name: 'TimeoutError' })
    } finally {
      vi.useRealTimers()
    }
  })

  // Three directions on the NON-ok body read. problemOf swallows a failed read by design, so
  // without the explicit signal check all three collapse into "the server answered 500" -- and a
  // query useNearbyPois cancelled itself would paint the map with an error it did not suffer.
  it('a TIMEOUT during a non-ok body read surfaces as TimeoutError, not as the status', async () => {
    vi.useFakeTimers()
    try {
      const { apiFetchJson } = await import('@/lib/apiFetch')
      vi.stubGlobal('fetch', hungBodyFetch([], 500))

      const outcome = apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(10_000)

      expect(await outcome).toMatchObject({ name: 'TimeoutError' })
    } finally {
      vi.useRealTimers()
    }
  })

  it("a CALLER ABORT during a non-ok body read surfaces as AbortError, not as the status", async () => {
    const { apiFetchJson } = await import('@/lib/apiFetch')
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', hungBodyFetch(signals, 500))
    const caller = new AbortController()

    const outcome = apiFetchJson('/api/spatial/foo', { signal: caller.signal }).catch((e: unknown) => e)
    await flushMicrotasks()
    expect(signals[0].aborted).toBe(false)

    caller.abort(new DOMException('superseded', 'AbortError'))

    expect(await outcome).toMatchObject({ name: 'AbortError', message: 'superseded' })
  })

  it("the caller's abort cancels a hung BODY read, not just a hung fetch", async () => {
    const { apiFetchJson } = await import('@/lib/apiFetch')
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', hungBodyFetch(signals))
    const caller = new AbortController()

    const outcome = apiFetchJson('/api/auth/settings', { signal: caller.signal }).catch((e: unknown) => e)
    await Promise.resolve()
    await Promise.resolve()
    expect(signals[0].aborted).toBe(false)

    caller.abort(new DOMException('session over', 'AbortError'))

    expect(await outcome).toMatchObject({ name: 'AbortError', message: 'session over' })
  })

  it('arms a FRESH window per attempt: a slow-but-successful refresh still leaves the retry a full budget', async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-1')
      const store = await import('@/lib/authStore')
      const { apiFetchJson } = await import('@/lib/apiFetch')
      store.setAccessToken('jwt-old')

      const retrySignals: AbortSignal[] = []
      let attempts = 0
      vi.stubGlobal(
        'fetch',
        vi.fn((url: unknown, init?: RequestInit) => {
          if (String(url).includes('/api/auth/refresh')) {
            // Slow but successful: 9 s of the 10 s /refresh is itself allowed.
            return new Promise<Response>((resolve) =>
              setTimeout(() => resolve(response(200, { accessToken: 'jwt-new' })), 9_000),
            )
          }
          attempts += 1
          if (attempts === 1) {
            return Promise.resolve(response(401))
          }
          const signal = init!.signal!
          retrySignals.push(signal)
          return new Promise<Response>((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)))
        }),
      )

      const outcome = apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(9_000)
      expect(retrySignals).toHaveLength(1)

      // 18_999 ms into the CALL, 9_999 ms into the retry: one shared window would have expired long ago.
      await vi.advanceTimersByTimeAsync(9_999)
      expect(retrySignals[0].aborted).toBe(false)

      await vi.advanceTimersByTimeAsync(1)

      expect(retrySignals[0].aborted).toBe(true)
      expect((retrySignals[0].reason as DOMException).name).toBe('TimeoutError')
      await outcome
    } finally {
      vi.useRealTimers()
    }
  })

  it('releases the attempt timer on a normal completion', async () => {
    vi.useFakeTimers()
    try {
      const { apiFetchJson } = await import('@/lib/apiFetch')
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200)))

      await apiFetchJson('/api/spatial/foo')

      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('releases the caller-signal listener on EVERY exit, not only when the caller aborts', async () => {
    const { apiFetchJson } = await import('@/lib/apiFetch')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200)))
    // The session signal from useCategorySelection lives until logout: one listener left behind per
    // completed settings call accumulates on it.
    const caller = new AbortController()
    const added = vi.spyOn(caller.signal, 'addEventListener')
    const removed = vi.spyOn(caller.signal, 'removeEventListener')

    for (let i = 0; i < 3; i += 1) {
      await apiFetchJson('/api/auth/settings', { signal: caller.signal })
    }

    expect(added).toHaveBeenCalledTimes(3)
    expect(removed).toHaveBeenCalledTimes(3)
  })

  it("the caller's abort cancels an in-flight attempt and carries the caller's reason", async () => {
    const { apiFetchJson } = await import('@/lib/apiFetch')
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', hungFetch(signals))
    const caller = new AbortController()

    const outcome = apiFetchJson('/api/spatial/foo', { signal: caller.signal }).catch((e: unknown) => e)
    await Promise.resolve()
    expect(signals[0].aborted).toBe(false)

    caller.abort(new DOMException('session over', 'AbortError'))

    expect(signals[0].aborted).toBe(true)
    // The reason must survive: useNearbyPois stays silent on an AbortError and surfaces a TimeoutError.
    expect(await outcome).toMatchObject({ name: 'AbortError', message: 'session over' })
  })

  it('an ALREADY-aborted caller signal aborts the FIRST attempt at once', async () => {
    const { apiFetchJson } = await import('@/lib/apiFetch')
    const signals: AbortSignal[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: unknown, init?: RequestInit) => {
        signals.push(init!.signal!)
        return Promise.resolve(response(200))
      }),
    )
    const caller = new AbortController()
    caller.abort(new DOMException('session over', 'AbortError'))

    await apiFetchJson('/api/spatial/foo', { signal: caller.signal })

    expect(signals[0].aborted).toBe(true)
    expect(signals[0].reason).toMatchObject({ name: 'AbortError', message: 'session over' })
  })

  /**
   * HIGH-1: the RETRY must be composed with the caller's signal too, not just the first attempt.
   * Drop it there and session.abort() stops cancelling the retried settings PUT -- and because the
   * retry re-reads getAccessToken() at attempt time, a logout+relogin inside the retry window would
   * send user B's token with user A's body (M2-17 LOW-1, reopened silently).
   */
  it("the RETRY is composed with the caller's signal: an abort during the refresh sends no second body", async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-1')
      const store = await import('@/lib/authStore')
      const { apiFetchJson } = await import('@/lib/apiFetch')
      store.setAccessToken('jwt-old')

      const sentBodies: unknown[] = []
      let attempts = 0
      const fetchMock = vi.fn((url: unknown, init?: RequestInit) => {
        if (String(url).includes('/api/auth/refresh')) {
          return new Promise<Response>((resolve) =>
            setTimeout(() => resolve(response(200, { accessToken: 'jwt-new' })), 9_000),
          )
        }
        attempts += 1
        if (attempts === 1) {
          return Promise.resolve(response(401))
        }
        const signal = init!.signal!
        // What a real fetch does with an already-aborted signal: reject, send nothing.
        if (signal.aborted) {
          return Promise.reject(signal.reason)
        }
        sentBodies.push(init!.body)
        return Promise.resolve(response(200, { ok: true }))
      })
      vi.stubGlobal('fetch', fetchMock)

      const caller = new AbortController()
      const outcome = apiFetchJson('/api/auth/settings', {
        method: 'PUT',
        body: JSON.stringify({ defaultContentMode: 'TEXT' }),
        signal: caller.signal,
      }).catch((e: unknown) => e)

      await vi.advanceTimersByTimeAsync(1_000)
      caller.abort(new DOMException('session over', 'AbortError'))
      await vi.advanceTimersByTimeAsync(9_000)

      expect(await outcome).toMatchObject({ name: 'AbortError', message: 'session over' })
      expect(sentBodies).toEqual([])
      const settingsCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('/settings'))
      expect(settingsCalls).toHaveLength(2)
      expect((settingsCalls[1][1] as RequestInit).signal!.aborted).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  /**
   * MEDIUM-2: the three budgets are strictly additive, because the refresh sits BETWEEN the two
   * attempt windows rather than inside either. ~30 s is accepted -- any total cap would hand the
   * post-refresh retry a remainder again, the dead retry the per-attempt window exists to kill.
   * Pinned so the figure cannot drift unnoticed.
   */
  it('the 401 → refresh → retry chain is additive: the caller waits 29 800 ms worst case', async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-1')
      const store = await import('@/lib/authStore')
      const { apiFetchJson } = await import('@/lib/apiFetch')
      store.setAccessToken('jwt-old')

      let attempts = 0
      vi.stubGlobal(
        'fetch',
        vi.fn((url: unknown, init?: RequestInit) => {
          if (String(url).includes('/api/auth/refresh')) {
            return new Promise<Response>((resolve) =>
              setTimeout(() => resolve(response(200, { accessToken: 'jwt-new' })), 9_900),
            )
          }
          attempts += 1
          if (attempts === 1) {
            return new Promise<Response>((resolve) => setTimeout(() => resolve(response(401)), 9_900))
          }
          const signal = init!.signal!
          return new Promise<Response>((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)))
        }),
      )

      const outcome = apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(29_799)
      expect(await settledOr(outcome, 'unsettled')).toBe('unsettled')

      await vi.advanceTimersByTimeAsync(1)

      expect(await outcome).toMatchObject({ name: 'TimeoutError' })
    } finally {
      vi.useRealTimers()
    }
  })

  // LOW-5: the refresh leg is deliberately NOT composed with the caller's signal, so an abandoned
  // call leaves a refresh running to completion. Accepted lateness -- pinned so it stays visible.
  it('a caller abort does not cancel the in-flight refresh, which still stores its token', async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-1')
      const store = await import('@/lib/authStore')
      const { apiFetchJson } = await import('@/lib/apiFetch')
      store.setAccessToken('jwt-old')

      let attempts = 0
      vi.stubGlobal(
        'fetch',
        vi.fn((url: unknown, init?: RequestInit) => {
          if (String(url).includes('/api/auth/refresh')) {
            return new Promise<Response>((resolve) =>
              setTimeout(() => resolve(response(200, { accessToken: 'jwt-new' })), 5_000),
            )
          }
          attempts += 1
          if (attempts === 1) {
            return Promise.resolve(response(401))
          }
          const signal = init!.signal!
          return signal.aborted ? Promise.reject(signal.reason) : Promise.resolve(response(200, { ok: true }))
        }),
      )

      const caller = new AbortController()
      const outcome = apiFetchJson('/api/spatial/foo', { signal: caller.signal }).catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(0)
      caller.abort(new DOMException('gone', 'AbortError'))
      await vi.advanceTimersByTimeAsync(5_000)

      expect(await outcome).toMatchObject({ name: 'AbortError' })
      expect(store.getAccessToken()).toBe('jwt-new')
    } finally {
      vi.useRealTimers()
    }
  })

  // ...and what stops an abandoned refresh from resurrecting a session is doRefresh's epoch guard,
  // not the abort. The ordering that makes it hold lives in another module, so pin the invariant.
  it('a logout while that refresh is in flight stops it storing the token (epoch guard)', async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-1')
      const store = await import('@/lib/authStore')
      const client = await import('@/lib/authClient')
      const { apiFetchJson } = await import('@/lib/apiFetch')
      store.setAccessToken('jwt-old')

      let attempts = 0
      vi.stubGlobal(
        'fetch',
        vi.fn((url: unknown) => {
          const u = String(url)
          if (u.includes('/api/auth/refresh')) {
            return new Promise<Response>((resolve) =>
              setTimeout(() => resolve(response(200, { accessToken: 'jwt-new' })), 5_000),
            )
          }
          if (u.includes('/api/auth/logout')) {
            return Promise.resolve(response(204))
          }
          attempts += 1
          return attempts === 1 ? Promise.resolve(response(401)) : Promise.resolve(response(200, { ok: true }))
        }),
      )

      const outcome = apiFetchJson('/api/spatial/foo').catch((e: unknown) => e)
      await vi.advanceTimersByTimeAsync(0)
      await client.logout()
      await vi.advanceTimersByTimeAsync(5_000)
      await outcome

      expect(store.getAccessToken()).toBeNull()
      expect(store.useAuthStore().isAuthenticated.value).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})
