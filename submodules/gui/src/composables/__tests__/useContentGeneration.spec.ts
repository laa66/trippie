import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref, type EffectScope } from 'vue'
import type { ContentResponse, ContentResult, GenerateResponse } from '@/lib/contentApi'
import type { NearbyItem } from '@/lib/nearbyApi'
import { POLL_CEILING_MS, POLL_INTERVAL_MS } from '@/lib/contentPoll'

const getContent = vi.fn<(poiId: string, mode: string, signal?: AbortSignal) => Promise<ContentResult<ContentResponse>>>()
const postGenerate = vi.fn<(poiId: string, mode: string, signal?: AbortSignal) => Promise<ContentResult<GenerateResponse>>>()
vi.mock('@/lib/contentApi', () => ({
  getContent: (...args: Parameters<typeof getContent>) => getContent(...args),
  postGenerate: (...args: Parameters<typeof postGenerate>) => postGenerate(...args),
}))

import { CONTENT_MODE, FIRST_POLL_DELAY_MS, useContentGeneration } from '@/composables/useContentGeneration'

const poi = (over: Partial<NearbyItem> = {}): NearbyItem => ({
  id: 'node/1',
  name: 'Pręgierz',
  category: 'attractions',
  lat: 51.1,
  lon: 17.03,
  distanceMeters: 40,
  unlocked: true,
  ...over,
})

const read = (content: ContentResponse): ContentResult<ContentResponse> => ({ kind: 'content', content })
const answer = (content: GenerateResponse): ContentResult<GenerateResponse> => ({ kind: 'content', content })
const PROCESSING = read({ status: 'PROCESSING' })
const ABSENT = read({ status: 'ABSENT' })

/** A request that answers only when told to, so a spec can hold it in flight. */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

let scope: EffectScope | null = null

function setup(initial: NearbyItem | null) {
  const selected = ref<NearbyItem | null>(initial)
  scope = effectScope()
  const content = scope.run(() => useContentGeneration(selected))!
  return { selected, content }
}

/** Runs the timers AND the promise chains they start, so a poll that fires also lands. */
async function elapse(ms: number): Promise<void> {
  await vi.advanceTimersByTimeAsync(ms)
}

async function settle(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0)
}

const requests = () => getContent.mock.calls.length + postGenerate.mock.calls.length

beforeEach(() => {
  vi.useFakeTimers()
  getContent.mockReset()
  postGenerate.mockReset()
  getContent.mockResolvedValue(ABSENT)
})

afterEach(() => {
  scope?.stop()
  scope = null
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('useContentGeneration on open', () => {
  it('sends TEXT in M4', () => {
    expect(CONTENT_MODE).toBe('TEXT')
  })

  it('reads once and never posts when an unlocked POI is selected', async () => {
    const { content } = setup(poi())
    await elapse(3 * POLL_CEILING_MS)

    expect(getContent).toHaveBeenCalledTimes(1)
    expect(getContent.mock.calls[0].slice(0, 2)).toEqual(['node/1', 'TEXT'])
    expect(postGenerate).not.toHaveBeenCalled()
    expect(content.state.value).toEqual({ kind: 'absent' })
  })

  it('sends nothing for a locked POI, and nothing with no POI', async () => {
    const locked = setup(poi({ unlocked: false }))
    await elapse(POLL_CEILING_MS)
    expect(locked.content.state.value).toEqual({ kind: 'locked' })
    scope?.stop()

    const none = setup(null)
    await elapse(POLL_CEILING_MS)
    expect(none.content.state.value).toEqual({ kind: 'idle' })
    expect(requests()).toBe(0)
  })

  it('is loading while the read is in flight, then shows the text', async () => {
    const pending = deferred<ContentResult<ContentResponse>>()
    getContent.mockReturnValueOnce(pending.promise)
    const { content } = setup(poi())
    await settle()
    expect(content.state.value).toEqual({ kind: 'loading' })

    pending.resolve(read({ status: 'COMPLETED', text: 'Przewodnik' }))
    await settle()
    expect(content.state.value).toEqual({ kind: 'completed', text: 'Przewodnik' })
  })

  it.each(['PERMANENT', 'TRANSIENT', 'QUOTA'] as const)('keeps the failure class %s apart', async (failedClass) => {
    getContent.mockResolvedValue(read({ status: 'FAILED', failedClass }))
    const { content } = setup(poi())
    await settle()
    expect(content.state.value).toEqual({ kind: 'failed', failedClass })
  })

  it('shows a 403 on an unlocked POI as locked, once, with nothing on the console', async () => {
    const consoleError = vi.spyOn(console, 'error')
    const consoleWarn = vi.spyOn(console, 'warn')
    getContent.mockResolvedValue({ kind: 'locked' })
    const { content } = setup(poi())
    await elapse(3 * POLL_CEILING_MS)

    expect(content.state.value).toEqual({ kind: 'locked' })
    expect(getContent).toHaveBeenCalledTimes(1)
    expect(consoleError).not.toHaveBeenCalled()
    expect(consoleWarn).not.toHaveBeenCalled()
  })

  it('shows a 503 on open as try-again, not locked, and waits for the user', async () => {
    getContent.mockResolvedValueOnce({ kind: 'unavailable' })
    const { content } = setup(poi())
    await elapse(3 * POLL_CEILING_MS)

    expect(content.state.value).toEqual({ kind: 'unavailable' })
    expect(getContent).toHaveBeenCalledTimes(1)

    content.refresh()
    await settle()
    expect(getContent).toHaveBeenCalledTimes(2)
    expect(postGenerate).not.toHaveBeenCalled()
    expect(content.state.value).toEqual({ kind: 'absent' })
  })

  it('reads a failure that is not a typed answer as try-again, quietly', async () => {
    const consoleError = vi.spyOn(console, 'error')
    getContent.mockRejectedValue(new Error('Malformed content response'))
    const { content } = setup(poi())
    await elapse(POLL_CEILING_MS)

    expect(content.state.value).toEqual({ kind: 'unavailable' })
    expect(getContent).toHaveBeenCalledTimes(1)
    expect(consoleError).not.toHaveBeenCalled()
  })

  it('follows a generation that is already running when the sheet opens', async () => {
    getContent.mockResolvedValueOnce(PROCESSING).mockResolvedValue(read({ status: 'COMPLETED', text: 'T' }))
    const { content } = setup(poi())
    await settle()
    expect(content.state.value).toEqual({ kind: 'processing' })

    await elapse(POLL_INTERVAL_MS)
    expect(getContent).toHaveBeenCalledTimes(2)
    expect(content.state.value).toEqual({ kind: 'completed', text: 'T' })
    expect(postGenerate).not.toHaveBeenCalled()
  })
})

describe('useContentGeneration generate', () => {
  it('posts on the user action, reads first after 1.5 s and then every 2 s', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    getContent.mockResolvedValue(PROCESSING)

    content.generate()
    await settle()
    expect(postGenerate).toHaveBeenCalledTimes(1)
    expect(postGenerate.mock.calls[0].slice(0, 2)).toEqual(['node/1', 'TEXT'])
    expect(content.state.value).toEqual({ kind: 'processing' })

    await elapse(FIRST_POLL_DELAY_MS - 1)
    expect(getContent).toHaveBeenCalledTimes(1)
    await elapse(1)
    expect(getContent).toHaveBeenCalledTimes(2)
    await elapse(POLL_INTERVAL_MS - 1)
    expect(getContent).toHaveBeenCalledTimes(2)
    await elapse(1)
    expect(getContent).toHaveBeenCalledTimes(3)
  })

  it('stops after the read that sees COMPLETED', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    getContent.mockResolvedValueOnce(PROCESSING).mockResolvedValue(read({ status: 'COMPLETED', text: 'T' }))

    content.generate()
    await elapse(FIRST_POLL_DELAY_MS + POLL_INTERVAL_MS)
    expect(content.state.value).toEqual({ kind: 'completed', text: 'T' })
    const reads = getContent.mock.calls.length

    await elapse(3 * POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(reads)
  })

  it('reads exactly once after a POST that answers COMPLETED', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'COMPLETED' }))
    getContent.mockResolvedValue(read({ status: 'COMPLETED', text: 'Gotowe' }))

    content.generate()
    await elapse(3 * POLL_CEILING_MS)

    expect(getContent).toHaveBeenCalledTimes(2)
    expect(postGenerate).toHaveBeenCalledTimes(1)
    expect(content.state.value).toEqual({ kind: 'completed', text: 'Gotowe' })
  })

  it('shows a FAILED answer to the POST without polling', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'FAILED', failedClass: 'QUOTA' }))

    content.generate()
    await elapse(POLL_CEILING_MS)
    expect(content.state.value).toEqual({ kind: 'failed', failedClass: 'QUOTA' })
    expect(getContent).toHaveBeenCalledTimes(1)
  })

  // The broker 503 returns the lease, so a read after it would show the state from before the
  // POST (ABSENT) and the poll machine would stop on it: the try-again comes from the POST itself.
  it('remembers try-again from a 503 on the POST and posts again on refresh', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValueOnce({ kind: 'unavailable' })

    content.generate()
    await elapse(3 * POLL_CEILING_MS)
    expect(content.state.value).toEqual({ kind: 'unavailable' })
    expect(getContent).toHaveBeenCalledTimes(1)

    postGenerate.mockResolvedValueOnce(answer({ status: 'PROCESSING' }))
    content.refresh()
    await settle()
    expect(postGenerate).toHaveBeenCalledTimes(2)
    expect(content.state.value).toEqual({ kind: 'processing' })
  })

  it('shows a 403 on the POST as locked without retrying', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue({ kind: 'locked' })

    content.generate()
    await elapse(3 * POLL_CEILING_MS)
    expect(content.state.value).toEqual({ kind: 'locked' })
    expect(requests()).toBe(2)
  })

  it('keeps polling through a 503 while processing, under the same ceiling', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    getContent.mockResolvedValueOnce({ kind: 'unavailable' }).mockResolvedValue(read({ status: 'COMPLETED', text: 'T' }))

    content.generate()
    await elapse(FIRST_POLL_DELAY_MS)
    expect(content.state.value).toEqual({ kind: 'processing' })
    await elapse(POLL_INTERVAL_MS)
    expect(content.state.value).toEqual({ kind: 'completed', text: 'T' })
  })

  it('sends nothing after the ceiling until the user refreshes, and that refresh posts', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    getContent.mockResolvedValue(PROCESSING)

    content.generate()
    await elapse(POLL_CEILING_MS)
    expect(content.state.value).toEqual({ kind: 'takingLong' })
    const reads = getContent.mock.calls.length
    // 1 on open, then 1.5 s, 3.5 s ... 59.5 s, and the last one clamped onto 60 s.
    expect(reads).toBe(1 + 30 + 1)

    await elapse(10 * POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(reads)
    expect(postGenerate).toHaveBeenCalledTimes(1)

    content.refresh()
    await settle()
    expect(postGenerate).toHaveBeenCalledTimes(2)
    expect(getContent).toHaveBeenCalledTimes(reads)
    expect(content.state.value).toEqual({ kind: 'processing' })
  })
})

describe('useContentGeneration refresh', () => {
  it('posts on refresh after the ceiling even when the poll began on open', async () => {
    getContent.mockResolvedValue(PROCESSING)
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    const { content } = setup(poi())
    await elapse(POLL_CEILING_MS)
    expect(content.state.value).toEqual({ kind: 'takingLong' })
    expect(postGenerate).not.toHaveBeenCalled()

    content.refresh()
    await settle()
    expect(postGenerate).toHaveBeenCalledTimes(1)
  })

  it('does not carry the POST of one POI over to the refresh of the next', async () => {
    const { selected, content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue({ kind: 'unavailable' })
    content.generate()
    await settle()
    expect(postGenerate).toHaveBeenCalledTimes(1)

    getContent.mockResolvedValue({ kind: 'unavailable' })
    selected.value = poi({ id: 'node/2' })
    await settle()
    content.refresh()
    await settle()

    expect(postGenerate.mock.calls.map((call) => call[0])).toEqual(['node/1'])
    expect(getContent.mock.calls.filter((call) => call[0] === 'node/2')).toHaveLength(2)
  })
})

describe('useContentGeneration one request, one loop', () => {
  it('ignores the action while a POST is in flight', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockReturnValue(new Promise(() => {}))

    content.generate()
    content.generate()
    content.refresh()
    await settle()
    expect(postGenerate).toHaveBeenCalledTimes(1)
  })

  it('ignores the action while a poll is pending or a read is in flight', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    const slow = deferred<ContentResult<ContentResponse>>()
    getContent.mockReturnValue(slow.promise)

    content.generate()
    await settle()
    content.generate()
    await elapse(FIRST_POLL_DELAY_MS)
    content.refresh()
    await settle()

    expect(postGenerate).toHaveBeenCalledTimes(1)
  })

  it('ignores a refresh while the read on open is in flight', async () => {
    getContent.mockReturnValue(new Promise(() => {}))
    const { content } = setup(poi())
    await settle()

    content.refresh()
    await settle()
    expect(getContent).toHaveBeenCalledTimes(1)
  })

  // The aborted read of the previous POI settles after the next one started: its cleanup must not
  // free the slot the new request holds.
  it('keeps the slot of the new request when the aborted one settles late', async () => {
    getContent.mockImplementationOnce(
      (_id, _mode, signal) =>
        new Promise((_resolve, reject) => signal!.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))),
    )
    getContent.mockReturnValue(new Promise(() => {}))
    const { selected, content } = setup(poi())
    await settle()

    selected.value = poi({ id: 'node/2' })
    await settle()
    expect(getContent).toHaveBeenCalledTimes(2)

    content.generate()
    await settle()
    expect(postGenerate).not.toHaveBeenCalled()
  })

  // apiFetchJson can hold one call for ~30 s; the next poll is armed only after an answer lands.
  it('never stacks reads behind a slow one', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    const slow = deferred<ContentResult<ContentResponse>>()
    getContent.mockReturnValue(slow.promise)

    content.generate()
    await elapse(FIRST_POLL_DELAY_MS)
    expect(getContent).toHaveBeenCalledTimes(2)

    await elapse(30_000)
    expect(getContent).toHaveBeenCalledTimes(2)

    slow.resolve(PROCESSING)
    await settle()
    await elapse(POLL_INTERVAL_MS)
    expect(getContent).toHaveBeenCalledTimes(3)
  })

  it('does not restart when the selection is re-pointed at a fresh copy of the same POI', async () => {
    const { selected } = setup(poi())
    await settle()

    selected.value = poi()
    await elapse(POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(1)
  })

  it('reads as soon as the selected POI turns unlocked', async () => {
    const { selected, content } = setup(poi({ unlocked: false }))
    await settle()
    expect(getContent).not.toHaveBeenCalled()

    selected.value = poi({ unlocked: true })
    await settle()
    expect(getContent).toHaveBeenCalledTimes(1)
    expect(content.state.value).toEqual({ kind: 'absent' })
  })
})

describe('useContentGeneration cancellation', () => {
  async function processing() {
    const ctx = setup(poi())
    await settle()
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    getContent.mockResolvedValue(PROCESSING)
    ctx.content.generate()
    await elapse(FIRST_POLL_DELAY_MS)
    return ctx
  }

  it('cancels the poll of the previous POI when another is selected', async () => {
    const { selected, content } = await processing()
    getContent.mockResolvedValue(ABSENT)

    selected.value = poi({ id: 'node/2' })
    await elapse(3 * POLL_CEILING_MS)

    const ids = getContent.mock.calls.map((call) => call[0])
    expect(ids.filter((id) => id === 'node/2')).toHaveLength(1)
    expect(ids.filter((id) => id === 'node/1')).toHaveLength(2)
    expect(content.state.value).toEqual({ kind: 'absent' })
  })

  it('aborts the request of the previous POI and drops its late answer', async () => {
    const { selected, content } = setup(poi())
    await settle()
    const stale = deferred<ContentResult<ContentResponse>>()
    getContent.mockReturnValueOnce(stale.promise)
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    content.generate()
    await elapse(FIRST_POLL_DELAY_MS)
    const signal = getContent.mock.calls[1][2]!

    getContent.mockResolvedValue(ABSENT)
    selected.value = poi({ id: 'node/2' })
    await settle()
    expect(signal.aborted).toBe(true)

    stale.resolve(read({ status: 'COMPLETED', text: 'not this one' }))
    await elapse(POLL_CEILING_MS)
    expect(content.state.value).toEqual({ kind: 'absent' })
  })

  it('stops the poll and aborts the request when the sheet closes', async () => {
    const { selected, content } = setup(poi())
    await settle()
    getContent.mockReturnValue(new Promise(() => {}))
    postGenerate.mockResolvedValue(answer({ status: 'PROCESSING' }))
    content.generate()
    await elapse(FIRST_POLL_DELAY_MS)
    const signal = getContent.mock.calls[1][2]!

    selected.value = null
    await settle()
    expect(signal.aborted).toBe(true)
    expect(content.state.value).toEqual({ kind: 'idle' })
  })

  it('stop() drops a pending poll', async () => {
    const { content } = await processing()
    const reads = getContent.mock.calls.length

    content.stop()
    await elapse(3 * POLL_CEILING_MS)
    expect(getContent).toHaveBeenCalledTimes(reads)
  })

  it('stop() aborts the request in flight', async () => {
    const { content } = setup(poi())
    await settle()
    postGenerate.mockReturnValue(new Promise(() => {}))
    content.generate()
    await settle()

    content.stop()
    expect(postGenerate.mock.calls[0][2]!.aborted).toBe(true)
  })

  it('drops the late answer of a request it aborted', async () => {
    const pending = deferred<ContentResult<ContentResponse>>()
    getContent.mockReturnValueOnce(pending.promise)
    const { content } = setup(poi())
    await settle()

    content.stop()
    pending.resolve(read({ status: 'COMPLETED', text: 'late' }))
    await nextTick()
    await settle()
    expect(content.state.value).toEqual({ kind: 'loading' })
  })
})
