import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, setAccessToken } from '@/lib/authStore'
import { getContent, postGenerate } from '@/lib/contentApi'

function res(status: number, body: unknown): Response {
  return { ok: status >= 200 && status < 300, status, json: async () => body } as unknown as Response
}

function stubFetch(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(res(status, body))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function stubHanging(): AbortSignal[] {
  const signals: AbortSignal[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((_url: string, init?: RequestInit) => {
      const signal = init!.signal!
      signals.push(signal)
      return new Promise<Response>((_resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)))
    }),
  )
  return signals
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearSession()
})

describe('getContent request', () => {
  it('GETs the origin-relative content URL with poiId and mode in the query', async () => {
    const fetchMock = stubFetch(200, { status: 'ABSENT' })

    await getContent('node/1', 'TEXT')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/content/content?poiId=node%2F1&mode=TEXT')
    expect(init.method).toBeUndefined()
    expect(init.body).toBeUndefined()
  })

  it('URL-encodes an osm id with a slash so it stays one query value', async () => {
    const fetchMock = stubFetch(200, { status: 'ABSENT' })

    await getContent('way/476702262', 'TEXT')

    const url = fetchMock.mock.calls[0][0] as string
    expect(url).toContain('poiId=way%2F476702262')
    expect(url).not.toContain('poiId=way/')
    expect(new URL(url, 'http://x').searchParams.get('poiId')).toBe('way/476702262')
  })

  it('sends the mode it was given', async () => {
    const fetchMock = stubFetch(200, { status: 'ABSENT' })

    await getContent('node/1', 'AUDIO')

    expect(new URL(fetchMock.mock.calls[0][0] as string, 'http://x').searchParams.get('mode')).toBe('AUDIO')
  })

  it('goes through apiFetchJson: the Bearer rides along', async () => {
    setAccessToken('jwt-c')
    const fetchMock = stubFetch(200, { status: 'ABSENT' })

    await getContent('node/1', 'TEXT')

    expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get('Authorization')).toBe('Bearer jwt-c')
  })

  it('the caller signal aborts an in-flight read', async () => {
    const pending = stubHanging()
    const caller = new AbortController()

    const read = getContent('node/1', 'TEXT', caller.signal).catch((e: unknown) => e)
    await Promise.resolve()
    caller.abort(new DOMException('session over', 'AbortError'))

    expect(pending[0].aborted).toBe(true)
    expect(await read).toMatchObject({ name: 'AbortError' })
  })
})

describe('getContent response', () => {
  it('ABSENT', async () => {
    stubFetch(200, { status: 'ABSENT' })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({ kind: 'content', content: { status: 'ABSENT' } })
  })

  it('PROCESSING', async () => {
    stubFetch(200, { status: 'PROCESSING' })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({ kind: 'content', content: { status: 'PROCESSING' } })
  })

  it('COMPLETED carries its text', async () => {
    stubFetch(200, { status: 'COMPLETED', text: 'Wroclaw opera' })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({
      kind: 'content',
      content: { status: 'COMPLETED', text: 'Wroclaw opera' },
    })
  })

  it.each(['TRANSIENT', 'PERMANENT', 'QUOTA'] as const)('FAILED carries failedClass %s', async (failedClass) => {
    stubFetch(200, { status: 'FAILED', failedClass })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({
      kind: 'content',
      content: { status: 'FAILED', failedClass },
    })
  })
})

describe('getContent boundary pins', () => {
  it('an unknown status throws', async () => {
    stubFetch(200, { status: 'QUEUED' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('a missing status throws', async () => {
    stubFetch(200, {})
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('a null body throws', async () => {
    stubFetch(200, null)
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('a non-object body throws', async () => {
    stubFetch(200, 'COMPLETED')
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('COMPLETED without text throws', async () => {
    stubFetch(200, { status: 'COMPLETED' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('COMPLETED with a non-string text throws', async () => {
    stubFetch(200, { status: 'COMPLETED', text: 7 })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('COMPLETED with a null text throws', async () => {
    stubFetch(200, { status: 'COMPLETED', text: null })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('COMPLETED with an empty text is accepted: the type is the pin, not the length', async () => {
    stubFetch(200, { status: 'COMPLETED', text: '' })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({ kind: 'content', content: { status: 'COMPLETED', text: '' } })
  })

  it('COMPLETED with a failedClass throws', async () => {
    stubFetch(200, { status: 'COMPLETED', text: 't', failedClass: 'QUOTA' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('PROCESSING with a text throws', async () => {
    stubFetch(200, { status: 'PROCESSING', text: 'stale' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('PROCESSING with a failedClass throws', async () => {
    stubFetch(200, { status: 'PROCESSING', failedClass: 'TRANSIENT' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('PROCESSING with a null text throws: the server omits a field, it does not null it', async () => {
    stubFetch(200, { status: 'PROCESSING', text: null })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('PROCESSING with a null failedClass throws', async () => {
    stubFetch(200, { status: 'PROCESSING', failedClass: null })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('COMPLETED with a null failedClass throws', async () => {
    stubFetch(200, { status: 'COMPLETED', text: 't', failedClass: null })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('FAILED with a null text throws', async () => {
    stubFetch(200, { status: 'FAILED', failedClass: 'QUOTA', text: null })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('ABSENT with a text throws', async () => {
    stubFetch(200, { status: 'ABSENT', text: 'stale' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('ABSENT with a failedClass throws', async () => {
    stubFetch(200, { status: 'ABSENT', failedClass: 'PERMANENT' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('FAILED without failedClass throws', async () => {
    stubFetch(200, { status: 'FAILED' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('FAILED with an unknown failedClass throws', async () => {
    stubFetch(200, { status: 'FAILED', failedClass: 'WEIRD' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('FAILED with a null failedClass throws', async () => {
    stubFetch(200, { status: 'FAILED', failedClass: null })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })

  it('FAILED with a text throws: a paid text must not hide behind a failure', async () => {
    stubFetch(200, { status: 'FAILED', failedClass: 'TRANSIENT', text: 'old' })
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow(/Malformed content/)
  })
})

describe('getContent errors', () => {
  it('403 is the terminal locked result, not an error', async () => {
    stubFetch(403, { detail: 'POI is not unlocked' })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({ kind: 'locked' })
  })

  it('503 is the retryable unavailable result', async () => {
    stubFetch(503, { detail: 'Unlock state is temporarily unavailable' })
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({ kind: 'unavailable' })
  })

  it('422 is the unsupported result', async () => {
    stubFetch(422, { detail: 'Only TEXT content is available' })
    await expect(getContent('node/1', 'AUDIO')).resolves.toEqual({ kind: 'unsupported' })
  })

  it.each([400, 404, 500])('%i throws AuthApiError with its status', async (status) => {
    stubFetch(status, { detail: 'nope' })
    await expect(getContent('node/1', 'TEXT')).rejects.toMatchObject({ status, detail: 'nope' })
  })

  it('a 403 with a non-JSON body is still locked: the status decides', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403, json: async () => { throw new Error('not json') } }))
    await expect(getContent('node/1', 'TEXT')).resolves.toEqual({ kind: 'locked' })
  })
})

describe('transport failures are not answers', () => {
  it('a network failure on GET is rethrown, not mapped to a result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network down')))
    await expect(getContent('node/1', 'TEXT')).rejects.toThrow('network down')
  })

  it('a network failure on POST is rethrown, not mapped to a result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network down')))
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow('network down')
  })
})

describe('postGenerate request', () => {
  it('POSTs the JSON body to the origin-relative generate URL', async () => {
    const fetchMock = stubFetch(202, { status: 'PROCESSING' })

    await postGenerate('way/476702262', 'TEXT')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/content/content/generate')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json')
    expect(JSON.parse(init.body as string)).toEqual({ poiId: 'way/476702262', mode: 'TEXT' })
  })

  it('sends the mode it was given in the body', async () => {
    const fetchMock = stubFetch(202, { status: 'PROCESSING' })

    await postGenerate('node/1', 'AUDIO')

    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)).toEqual({ poiId: 'node/1', mode: 'AUDIO' })
  })

  it('goes through apiFetchJson: the Bearer rides along', async () => {
    setAccessToken('jwt-c')
    const fetchMock = stubFetch(202, { status: 'PROCESSING' })

    await postGenerate('node/1', 'TEXT')

    expect(new Headers((fetchMock.mock.calls[0][1] as RequestInit).headers).get('Authorization')).toBe('Bearer jwt-c')
  })

  it('the caller signal aborts an in-flight request', async () => {
    const pending = stubHanging()
    const caller = new AbortController()

    const write = postGenerate('node/1', 'TEXT', caller.signal).catch((e: unknown) => e)
    await Promise.resolve()
    caller.abort(new DOMException('session over', 'AbortError'))

    expect(pending[0].aborted).toBe(true)
    expect(await write).toMatchObject({ name: 'AbortError' })
  })
})

describe('postGenerate response', () => {
  it('202 PROCESSING', async () => {
    stubFetch(202, { status: 'PROCESSING' })
    await expect(postGenerate('node/1', 'TEXT')).resolves.toEqual({ kind: 'content', content: { status: 'PROCESSING' } })
  })

  it('200 COMPLETED carries no text', async () => {
    stubFetch(200, { status: 'COMPLETED' })
    await expect(postGenerate('node/1', 'TEXT')).resolves.toEqual({ kind: 'content', content: { status: 'COMPLETED' } })
  })

  it.each(['TRANSIENT', 'PERMANENT', 'QUOTA'] as const)('200 FAILED carries failedClass %s', async (failedClass) => {
    stubFetch(200, { status: 'FAILED', failedClass })
    await expect(postGenerate('node/1', 'TEXT')).resolves.toEqual({ kind: 'content', content: { status: 'FAILED', failedClass } })
  })
})

describe('postGenerate boundary pins', () => {
  it('an unknown status throws', async () => {
    stubFetch(200, { status: 'QUEUED' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('a missing status throws', async () => {
    stubFetch(200, {})
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('a null body throws', async () => {
    stubFetch(200, null)
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('a non-object body throws', async () => {
    stubFetch(200, 'COMPLETED')
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('ABSENT is not an answer to a request: throws', async () => {
    stubFetch(200, { status: 'ABSENT' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('COMPLETED with a text throws: the text is read with GET', async () => {
    stubFetch(200, { status: 'COMPLETED', text: 'x' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('PROCESSING with a text throws', async () => {
    stubFetch(202, { status: 'PROCESSING', text: 'x' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('FAILED with a text throws', async () => {
    stubFetch(200, { status: 'FAILED', failedClass: 'QUOTA', text: 'x' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('PROCESSING with a failedClass throws', async () => {
    stubFetch(202, { status: 'PROCESSING', failedClass: 'TRANSIENT' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('PROCESSING with a null text throws', async () => {
    stubFetch(202, { status: 'PROCESSING', text: null })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('PROCESSING with a null failedClass throws', async () => {
    stubFetch(202, { status: 'PROCESSING', failedClass: null })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('COMPLETED with a failedClass throws', async () => {
    stubFetch(200, { status: 'COMPLETED', failedClass: 'TRANSIENT' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('FAILED without failedClass throws', async () => {
    stubFetch(200, { status: 'FAILED' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })

  it('FAILED with an unknown failedClass throws', async () => {
    stubFetch(200, { status: 'FAILED', failedClass: 'WEIRD' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toThrow(/Malformed generate/)
  })
})

describe('postGenerate errors', () => {
  it('403 is the terminal locked result', async () => {
    stubFetch(403, { detail: 'POI is not unlocked' })
    await expect(postGenerate('node/1', 'TEXT')).resolves.toEqual({ kind: 'locked' })
  })

  it('503 is the retryable unavailable result', async () => {
    stubFetch(503, { detail: 'Content generation is temporarily unavailable' })
    await expect(postGenerate('node/1', 'TEXT')).resolves.toEqual({ kind: 'unavailable' })
  })

  it('422 is the unsupported result', async () => {
    stubFetch(422, { detail: 'Only TEXT content is available' })
    await expect(postGenerate('node/1', 'AUDIO')).resolves.toEqual({ kind: 'unsupported' })
  })

  it.each([400, 404, 500])('%i throws AuthApiError with its status', async (status) => {
    stubFetch(status, { detail: 'nope' })
    await expect(postGenerate('node/1', 'TEXT')).rejects.toMatchObject({ status, detail: 'nope' })
  })
})
