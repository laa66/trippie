import { afterEach, describe, expect, it, vi } from 'vitest'
import { postUnlock, type UnlockRequest, type UnlockResponse } from '@/lib/unlockApi'

const REQUEST: UnlockRequest = {
  lat: 51.1079,
  lon: 17.0385,
  accuracyMeters: 12.5,
  osmIds: ['node/1', 'node/2'],
}

const BODY: UnlockResponse = { unlocked: ['node/1'], refused: ['node/2'] }

function okFetch(body: unknown = BODY) {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('postUnlock', () => {
  it('POSTs the asserted position and ids as JSON to the origin-relative unlock URL', async () => {
    const fetchMock = okFetch()

    await postUnlock(REQUEST)

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/spatial/locations/unlock')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual(REQUEST)
  })

  it('carries the Bearer-token treatment of apiFetchJson by going through it', async () => {
    const fetchMock = okFetch()

    await postUnlock(REQUEST)

    // Protected same-origin /api/** call: the Authorization header is apiFetchJson's job, and it is
    // only absent here because no session is set up in this spec. What this pins is that the call
    // is NOT a raw fetch, which would skip the 401 → refresh → retry chain entirely.
    expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).has('Authorization')).toBe(false)
  })

  it('forwards the abort signal', async () => {
    const fetchMock = okFetch()
    const controller = new AbortController()

    await postUnlock(REQUEST, controller.signal)

    expect(fetchMock.mock.calls[0][1]).toMatchObject({ signal: controller.signal })
  })

  it('parses a 200 body to the typed shape', async () => {
    okFetch()
    await expect(postUnlock(REQUEST)).resolves.toEqual(BODY)
  })

  it('accepts a 200 with both lists empty', async () => {
    okFetch({ unlocked: [], refused: [] })
    await expect(postUnlock(REQUEST)).resolves.toEqual({ unlocked: [], refused: [] })
  })

  it.each([
    [400, 'a malformed batch'],
    [422, 'an accuracy too coarse for the whole request'],
  ])('throws with status %s machine-readable (%s)', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status, json: async () => ({}) }))

    await expect(postUnlock(REQUEST)).rejects.toMatchObject({ status })
  })

  // The boundary, exactly as asNearby's: the caller iterates `unlocked` to move POIs into its
  // known-unlocked set, so `{}` would land `undefined` there and throw a TypeError one layer too
  // late, while a `null` body would do the same at the first property read. An unlock that fails to
  // stick is invisible: the POI is simply offered again on every fix.
  it('rejects a malformed 200 body before it reaches the caller', async () => {
    const malformed: unknown[] = [
      null,
      {},
      { unlocked: ['a'] },
      { refused: ['a'] },
      { unlocked: 'a', refused: [] },
      { unlocked: [], refused: 'a' },
      { unlocked: [1], refused: [] },
      { unlocked: [], refused: [null] },
      { unlocked: [], refused: [{ osmId: 'a' }] },
    ]

    for (const body of malformed) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => body }))
      await expect(postUnlock(REQUEST)).rejects.toThrow(/Malformed unlock response/)
    }
  })
})
