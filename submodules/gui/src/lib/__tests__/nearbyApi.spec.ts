import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchNearby, type NearbyResponse } from '@/lib/nearbyApi'

const BODY: NearbyResponse = {
  items: [
    { id: 'a1', name: 'Rynek', category: 'attractions', lat: 51.11, lon: 17.03, distanceMeters: 42 },
    { id: 'a2', name: null, category: 'monuments', lat: 51.12, lon: 17.04, distanceMeters: 130 },
  ],
  truncated: false,
}

function okFetch(body: unknown = BODY) {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => body,
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('fetchNearby', () => {
  it('builds the origin-relative URL with lat/lon/radius/categories CSV/limit', async () => {
    const fetchMock = okFetch()

    await fetchNearby({
      lat: 51.1079,
      lon: 17.0385,
      radius: 1200,
      categories: ['museums', 'monuments'],
      limit: 50,
    })

    const calledUrl = fetchMock.mock.calls[0][0] as string
    expect(calledUrl.startsWith('/api/spatial/locations/nearby?')).toBe(true)

    const params = new URL(calledUrl, 'http://x').searchParams
    expect(params.get('lat')).toBe('51.1079')
    expect(params.get('lon')).toBe('17.0385')
    expect(params.get('radius')).toBe('1200')
    expect(params.get('categories')).toBe('museums,monuments')
    expect(params.get('limit')).toBe('50')
  })

  it('omits limit when not provided', async () => {
    const fetchMock = okFetch()

    await fetchNearby({ lat: 51, lon: 17, radius: 500, categories: ['sacred'] })

    const params = new URL(fetchMock.mock.calls[0][0] as string, 'http://x').searchParams
    expect(params.has('limit')).toBe(false)
  })

  it('forwards the abort signal to fetch', async () => {
    const fetchMock = okFetch()
    const controller = new AbortController()

    await fetchNearby({ lat: 51, lon: 17, radius: 500, categories: ['sacred'], signal: controller.signal })

    expect(fetchMock.mock.calls[0][1]).toMatchObject({ signal: controller.signal })
  })

  it('parses a 200 body to the typed shape', async () => {
    okFetch()
    const res = await fetchNearby({ lat: 51, lon: 17, radius: 500, categories: ['attractions'] })
    expect(res).toEqual(BODY)
  })

  it('throws (including the status) on a non-2xx response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 400, json: async () => ({}) }))
    await expect(
      fetchNearby({ lat: 999, lon: 17, radius: 500, categories: ['attractions'] }),
    ).rejects.toThrow(/400/)
  })
})
