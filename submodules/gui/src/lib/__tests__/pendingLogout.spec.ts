import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * M2-19 (C), criteria 6-13: a logout whose server leg did not answer 204 leaves a durable marker,
 * and the next boot replays the bearer-less logout instead of silent-refreshing.
 *
 * Each test re-imports the modules after `vi.resetModules()`, so the module-singleton auth state
 * (token, latch, memoized boot promise) starts clean — a fresh import registry is also exactly how
 * criterion 13 simulates a page reload.
 */

const MARKER_KEY = 'trippie.pendingLogout'

/**
 * In-memory localStorage stand-in, stubbed globally — the convention this repo already uses in
 * useCategorySelection's specs, because Node's own `localStorage` global shadows jsdom's and reads
 * back undefined under vitest.
 */
function memoryStorage(): Storage & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return {
    map,
    length: 0,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: (i: number) => [...map.keys()][i] ?? null,
  } as unknown as Storage & { map: Map<string, string> }
}

let storage: Storage & { map: Map<string, string> }

function jsonResponse(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

function noContent(): Response {
  return { ok: true, status: 204, json: async () => null } as unknown as Response
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

/** Resolves to the promise's value if it has already settled, else to the marker. */
function settledOr<T>(promise: Promise<T>, marker: string): Promise<T | string> {
  return Promise.race([promise, Promise.resolve(marker)])
}

function setCsrfCookie(value: string): void {
  document.cookie = `csrf=${value}; Path=/`
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`))
  return match ? match[1] : null
}

function marker(): string | null {
  return storage.getItem(MARKER_KEY)
}

function urlsOf(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return (fetchMock.mock.calls as unknown as [string, RequestInit][]).map(([u]) => u)
}

beforeEach(() => {
  vi.resetModules()
  storage = memoryStorage()
  vi.stubGlobal('localStorage', storage)
})

afterEach(() => {
  document.cookie = 'csrf=; Path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('pendingLogout marker (criterion 6)', () => {
  it('a 204 logout writes no marker and leaves the csrf cookie for the server to clear', async () => {
    setCsrfCookie('csrf-live')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContent()))

    await client.logout()

    expect(marker()).toBeNull()
    expect(readCookie('csrf')).toBe('csrf-live')
  })

  it.each([
    ['500 (AuthApiError-shaped server failure)', async () => jsonResponse(500, { detail: 'boom' })],
    ['403 (csrf rejected)', async () => jsonResponse(403, {})],
    ['200 (a non-204 success is still no proof of revocation)', async () => jsonResponse(200, {})],
    [
      'a thrown network error',
      async () => {
        throw new TypeError('offline')
      },
    ],
  ])('a logout answering %s writes the marker and deletes the csrf cookie', async (_name, leg) => {
    setCsrfCookie('csrf-live')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    vi.stubGlobal('fetch', vi.fn(leg))

    await client.logout()

    expect(marker()).not.toBeNull()
    expect(readCookie('csrf')).toBeNull()
    // the local session is dropped exactly as before, whatever the server said
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  it('the marker is a bare timestamp — no token, no sub, no email anywhere in storage', async () => {
    setCsrfCookie('csrf-live')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-secret-material')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(500, {})))

    await client.logout()

    const value = marker()
    expect(value).toMatch(/^\d+$/)
    expect(Number(value)).toBeGreaterThan(0)
    // nothing else was persisted either: one key, one timestamp
    expect([...storage.map.entries()]).toEqual([[MARKER_KEY, value]])
  })

  it('a logout that times out after 5 s writes the marker', async () => {
    vi.useFakeTimers()
    try {
      setCsrfCookie('csrf-live')
      const store = await import('@/lib/authStore')
      const client = await import('@/lib/authClient')
      store.setAccessToken('jwt-live')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const pending = client.logout()
      await vi.advanceTimersByTimeAsync(5_000)
      await pending

      expect(signals[0].aborted).toBe(true)
      expect(marker()).not.toBeNull()
      expect(readCookie('csrf')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('a 401 logout that refreshes and resends successfully writes no marker', async () => {
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
      return noContent()
    })
    vi.stubGlobal('fetch', fetchMock)

    await client.logout()

    expect(urlsOf(fetchMock)).toEqual(['/api/auth/logout', '/api/auth/refresh', '/api/auth/logout'])
    expect(marker()).toBeNull()
  })

  it('a 401 logout whose refresh also fails writes the marker (the resend never happened)', async () => {
    setCsrfCookie('csrf-old')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-expired')
    const fetchMock = vi.fn(async (url: string) =>
      url === '/api/auth/refresh' ? jsonResponse(401, {}) : jsonResponse(401, {}),
    )
    vi.stubGlobal('fetch', fetchMock)

    await client.logout()

    expect(urlsOf(fetchMock)).toEqual(['/api/auth/logout', '/api/auth/refresh'])
    expect(marker()).not.toBeNull()
  })
})

describe('boot with a pending-logout marker (criteria 7, 8, 10, 13)', () => {
  /**
   * CRITERION 13 — the repro. A failed logout, then a fresh module registry standing in for the
   * reload: pre-fix this boot fired the memoized `/refresh`, the surviving httpOnly refresh cookie
   * answered it, and the session came back on what may be a shared device. This test fails against
   * pre-fix code (it would see a `/refresh` call and `isAuthenticated === true`).
   */
  it('a failed logout then a reload issues NO /refresh and stays logged out', async () => {
    setCsrfCookie('csrf-live')
    {
      const store = await import('@/lib/authStore')
      const client = await import('@/lib/authClient')
      store.setAccessToken('jwt-live')
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(503, {})))
      await client.logout()
      expect(marker()).not.toBeNull()
    }

    // ---- reload: fresh module registry, in-memory token and latch both gone ----
    vi.resetModules()
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(noContent())
    vi.stubGlobal('fetch', fetchMock)

    // main.ts boot order
    const replay = client.resumePendingLogout()
    const resolved = await client.ensureSessionResolved()
    await replay

    expect(resolved).toBe(false)
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
    expect(store.getAccessToken()).toBeNull()
    expect(urlsOf(fetchMock)).not.toContain('/api/auth/refresh')
  })

  /**
   * Criterion 7 pinned on its own. The repro above boots in main.ts order, where
   * {@link resumePendingLogout} has already latched the store — so it would survive a removal of the
   * marker check in {@link ensureSessionResolved} (verified by mutation). This one exercises the
   * check with nothing else in play: the two suppressions are independent and both are required.
   */
  it('ensureSessionResolved alone issues no /refresh with the marker set, without the replay having latched', async () => {
    setCsrfCookie('csrf-live')
    storage.setItem(MARKER_KEY, '1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'resurrected' }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await client.ensureSessionResolved()).toBe(false)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  /**
   * HIGH-1 (Light, M2-19 review). {@link refreshAccessToken} is the real choke point — it is on the
   * path of EVERY refresh, including the one `apiFetch` runs on a 401, which can arrive before the
   * nav guard has ever called {@link ensureSessionResolved}. Checking the marker only in the latter
   * left the session resurrectable: `refreshAccessToken` consulted the in-memory latch alone, and
   * the latch does not survive a reload while the marker does. Red if the `hasPendingLogout()` half
   * of that guard is removed.
   */
  it('refreshAccessToken refuses outright with the marker set, with no latch and no prior boot call', async () => {
    setCsrfCookie('csrf-live')
    storage.setItem(MARKER_KEY, '1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'resurrected' }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await client.refreshAccessToken()).toBe(false)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  it('an apiFetch 401 after a reload cannot resurrect the session through the retry refresh', async () => {
    setCsrfCookie('csrf-live')
    storage.setItem(MARKER_KEY, '1')
    const store = await import('@/lib/authStore')
    const { apiFetchJson } = await import('@/lib/apiFetch')
    const fetchMock = vi.fn(async (url: string) =>
      url === '/api/auth/refresh' ? jsonResponse(200, { accessToken: 'resurrected' }) : jsonResponse(401, {}),
    )
    vi.stubGlobal('fetch', fetchMock)

    const failure = await apiFetchJson('/api/spatial/locations/nearby').catch((e: unknown) => e)

    expect(failure).toMatchObject({ status: 401 })
    expect(urlsOf(fetchMock)).toEqual(['/api/spatial/locations/nearby'])
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })

  /**
   * MEDIUM-2 (Light): the "no bearer" assertion is load-bearing, so the store holds a token while
   * the replay runs — otherwise the assertion cannot tell "never attaches one" from "attaches one if
   * it has one" and a mutation that adds the header survives. It matters beyond tidiness: a public
   * POST carrying a stale bearer is rejected 401 AT THE GATEWAY and never reaches auth, so the
   * regression would silently break the replay rather than degrade it.
   */
  it('the replay is a bearer-less POST /logout echoing a freshly minted csrf cookie, even with a token in memory', async () => {
    storage.setItem(MARKER_KEY, String(Date.now()))
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-still-in-memory')
    // the cookie jar is read AT REQUEST TIME: the replay clears the csrf cookie afterwards
    let cookieOnTheWire: string | null = null
    const fetchMock = vi.fn(async () => {
      cookieOnTheWire = readCookie('csrf')
      return noContent()
    })
    vi.stubGlobal('fetch', fetchMock)

    await client.resumePendingLogout()

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/auth/logout')
    expect(init.method).toBe('POST')
    expect(init.credentials).toBe('same-origin')
    const headers = init.headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
    expect(Object.keys(headers)).toEqual(['X-CSRF-Token'])
    // the minted cookie and the echoed header must be the same value, or auth 403s the double-submit
    expect(headers['X-CSRF-Token']).toMatch(/^[0-9a-f]{64}$/)
    expect(cookieOnTheWire).toBe(headers['X-CSRF-Token'])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  /** MEDIUM-2's twin: the same invariant on the other public POST the client issues raw. */
  it('/refresh carries no bearer either, with a token in memory', async () => {
    setCsrfCookie('csrf-live')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-still-in-memory')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt-rotated' }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await client.refreshAccessToken()).toBe(true)

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/auth/refresh')
    const headers = init.headers as Record<string, string>
    expect(headers.Authorization).toBeUndefined()
    expect(Object.keys(headers)).toEqual(['X-CSRF-Token'])
  })

  it('mints a different csrf value on every replay (CSPRNG, not a constant)', async () => {
    const values: string[] = []
    for (let i = 0; i < 2; i++) {
      vi.resetModules()
      storage.setItem(MARKER_KEY, '1')
      const client = await import('@/lib/authClient')
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(502, {}))
      vi.stubGlobal('fetch', fetchMock)
      await client.resumePendingLogout()
      const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
      values.push((init.headers as Record<string, string>)['X-CSRF-Token'])
    }
    expect(values[0]).not.toBe(values[1])
  })

  it('no marker means no replay at all — not even a storage-driven request', async () => {
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(noContent())
    vi.stubGlobal('fetch', fetchMock)

    await client.resumePendingLogout()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('clears the marker ONLY on a 204, and drops its minted csrf cookie itself (criterion 8)', async () => {
    storage.setItem(MARKER_KEY, '1')
    const client = await import('@/lib/authClient')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContent()))

    await client.resumePendingLogout()

    expect(marker()).toBeNull()
    // auth's 204 also clears it via Max-Age=0, but the client does not depend on that: the whole
    // point of this half is not trusting the server leg beyond the status code.
    expect(readCookie('csrf')).toBeNull()
  })

  it.each([
    ['200 with a body (an aborted read must not pass as success)', async () => jsonResponse(200, null)],
    ['401 (the refresh token was already dead — sticky marker is the correct end state)', async () => jsonResponse(401, {})],
    ['403', async () => jsonResponse(403, {})],
    ['502', async () => jsonResponse(502, {})],
    [
      'a thrown network error',
      async () => {
        throw new TypeError('offline')
      },
    ],
  ])('keeps the marker when the replay answers %s (criterion 8)', async (_name, leg) => {
    storage.setItem(MARKER_KEY, '1')
    const client = await import('@/lib/authClient')
    vi.stubGlobal('fetch', vi.fn(leg))

    await client.resumePendingLogout()

    expect(marker()).not.toBeNull()
    // and the app is still logged out, so a repeatedly failing server never resurrects the session
    expect(await client.ensureSessionResolved()).toBe(false)
  })

  it('a replay that times out keeps the marker and never reads a body', async () => {
    vi.useFakeTimers()
    try {
      storage.setItem(MARKER_KEY, '1')
      const client = await import('@/lib/authClient')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const pending = client.resumePendingLogout()
      await vi.advanceTimersByTimeAsync(5_000)
      await pending

      expect(signals[0].aborted).toBe(true)
      expect(marker()).not.toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  /** CRITERION 10: the nav guard awaits ensureSessionResolved and must not stall behind the replay. */
  it('ensureSessionResolved resolves false immediately while the replay is still in flight', async () => {
    vi.useFakeTimers()
    try {
      storage.setItem(MARKER_KEY, '1')
      const client = await import('@/lib/authClient')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const replay = client.resumePendingLogout()
      const resolved = client.ensureSessionResolved()

      expect(await settledOr(resolved, 'unsettled')).toBe(false)

      await vi.advanceTimersByTimeAsync(5_000)
      await replay
    } finally {
      vi.useRealTimers()
    }
  })

  it('the nav guard sends a marked boot to /login without waiting for the replay', async () => {
    vi.useFakeTimers()
    try {
      storage.setItem(MARKER_KEY, '1')
      const client = await import('@/lib/authClient')
      const { authGuard } = await import('@/router/guard')
      const signals: AbortSignal[] = []
      vi.stubGlobal('fetch', hungFetch(signals))

      const replay = client.resumePendingLogout()
      const decision = authGuard({ meta: {} } as never)

      // Awaited with the fake clock FROZEN: the replay's fetch only settles when its 5 s timeout
      // aborts it, so a guard that waited for the replay would hang here instead of answering.
      expect(await decision).toBe('/login')

      await vi.advanceTimersByTimeAsync(5_000)
      await replay
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('boot without a marker (criterion 9)', () => {
  it('is unchanged: one memoized /refresh, same promise forever', async () => {
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'boot-jwt' }))
    vi.stubGlobal('fetch', fetchMock)

    await client.resumePendingLogout()
    const first = client.ensureSessionResolved()
    const second = client.ensureSessionResolved()

    expect(await first).toBe(true)
    expect(second).toBe(first)
    expect(urlsOf(fetchMock)).toEqual(['/api/auth/refresh'])
    expect(store.getAccessToken()).toBe('boot-jwt')
  })
})

describe('login clears the marker (criterion 11)', () => {
  it('a successful login after a failed logout leaves no lockout behind', async () => {
    setCsrfCookie('csrf-live')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    store.setAccessToken('jwt-live')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(500, {})))
    await client.logout()
    expect(marker()).not.toBeNull()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt-new' })))
    await client.login('a@b.c', 'pw12345678')

    expect(marker()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(true)
    // and the next boot refreshes normally again instead of replaying
    vi.resetModules()
    const reloaded = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'jwt-boot' }))
    vi.stubGlobal('fetch', fetchMock)
    await reloaded.resumePendingLogout()
    expect(await reloaded.ensureSessionResolved()).toBe(true)
    expect(urlsOf(fetchMock)).toEqual(['/api/auth/refresh'])
  })

  it('a failed login does NOT clear the marker', async () => {
    storage.setItem(MARKER_KEY, '1')
    const client = await import('@/lib/authClient')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401, {})))

    await expect(client.login('a@b.c', 'wrong')).rejects.toMatchObject({ status: 401 })

    expect(marker()).not.toBeNull()
  })
})

describe('a throwing localStorage (criterion 12)', () => {
  it('the app still boots and behaves exactly as it does today', async () => {
    const denied = (): never => {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    }
    vi.stubGlobal('localStorage', {
      getItem: denied,
      setItem: denied,
      removeItem: denied,
      clear: denied,
      key: denied,
      length: 0,
    })
    setCsrfCookie('csrf-1')
    const store = await import('@/lib/authStore')
    const client = await import('@/lib/authClient')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { accessToken: 'boot-jwt' }))
    vi.stubGlobal('fetch', fetchMock)

    // boot: an unreadable marker reads as "no marker" -> today's single memoized refresh
    await client.resumePendingLogout()
    expect(await client.ensureSessionResolved()).toBe(true)
    expect(urlsOf(fetchMock)).toEqual(['/api/auth/refresh'])
    expect(store.getAccessToken()).toBe('boot-jwt')

    // and a logout whose write cannot be persisted still drops the local session without throwing
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(500, {})))
    await expect(client.logout()).resolves.toBeUndefined()
    expect(store.getAccessToken()).toBeNull()
    expect(store.useAuthStore().isAuthenticated.value).toBe(false)
  })
})
