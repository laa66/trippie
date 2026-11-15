import { readonly, shallowRef, watch, type Ref } from 'vue'
import { getContent, postGenerate, type ContentResponse, type ContentResult, type FailedClass } from '@/lib/contentApi'
import { nextPollStep } from '@/lib/contentPoll'
import type { NearbyItem } from '@/lib/nearbyApi'
import type { ContentMode } from '@/lib/settingsApi'

/** M4 serves text only, whatever the user's `defaultContentMode` says; choosing the mode is M5. */
export const CONTENT_MODE: ContentMode = 'TEXT'

/** The first read after a POST comes sooner than the steady interval of {@link nextPollStep}. */
export const FIRST_POLL_DELAY_MS = 1_500

/**
 * What the sheet renders for the selected POI.
 *
 * - `idle`: no POI selected.
 * - `locked`: not unlocked per `nearby`, or a 403 on one that was — the race resolves towards
 *   unlocking, so it reads as the lock message and is never retried.
 * - `loading`: the free read made when the sheet opens.
 * - `absent`: unlocked, nothing generated yet.
 * - `processing`: generation asked for and being polled.
 * - `takingLong`: still processing at the poll ceiling. Nothing more is sent until `refresh`.
 * - `unavailable`: a 503, a timeout or a broken answer. Nothing is retried until `refresh`.
 * - `unsupported`: a 422 on the mode.
 */
export type ContentState =
  | { kind: 'idle' }
  | { kind: 'locked' }
  | { kind: 'loading' }
  | { kind: 'absent' }
  | { kind: 'processing' }
  | { kind: 'completed'; text: string }
  | { kind: 'failed'; failedClass: FailedClass }
  | { kind: 'takingLong' }
  | { kind: 'unavailable' }
  | { kind: 'unsupported' }

export interface UseContentGeneration {
  state: Readonly<Ref<ContentState>>
  /** The "Przewodnik" action. Nothing else POSTs, except a `refresh` after one. */
  generate: () => void
  /** A POST once one was made for this POI or the poll reached its ceiling, a GET otherwise. */
  refresh: () => void
  /** Aborts the request in flight and drops the pending poll. */
  stop: () => void
}

interface ContentKey {
  poiId: string
  mode: ContentMode
}

type Refusal = Exclude<ContentResult<never>, { kind: 'content' }>
type ReadOutcome = ContentResult<ContentResponse>

function refusalState(outcome: Refusal): ContentState {
  return { kind: outcome.kind }
}

function readState(outcome: ReadOutcome): ContentState {
  if (outcome.kind !== 'content') return refusalState(outcome)
  const content = outcome.content
  switch (content.status) {
    case 'ABSENT':
      return { kind: 'absent' }
    case 'PROCESSING':
      return { kind: 'processing' }
    case 'COMPLETED':
      return { kind: 'completed', text: content.text }
    case 'FAILED':
      return { kind: 'failed', failedClass: content.failedClass }
  }
}

/**
 * Content of the POI selected in MapView. One request in flight and one poll timer per instance,
 * both bound to the current (poiId, mode): a new key, a deselection or {@link stop} bumps `epoch`,
 * so an answer for an older key is dropped instead of overwriting the new one.
 *
 * The poll timer is armed only after an answer has landed, so a slow read (apiFetchJson can hold a
 * call for ~30 s) delays the next poll instead of stacking requests behind it.
 */
export function useContentGeneration(poi: Ref<NearbyItem | null>): UseContentGeneration {
  const state = shallowRef<ContentState>({ kind: 'idle' })

  let key: ContentKey | null = null
  let refreshPosts = false
  let epoch = 0
  let controller: AbortController | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let windowStartedAt = 0

  function stop(): void {
    epoch += 1
    controller?.abort()
    controller = null
    if (pollTimer !== null) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  function busy(): boolean {
    return controller !== null || pollTimer !== null
  }

  /** `null` when the answer belongs to a key that is no longer current. Any failure that is not
   *  one of the typed answers is something the user can only retry, so it reads as unavailable. */
  async function send<T>(
    request: (k: ContentKey, signal: AbortSignal) => Promise<ContentResult<T>>,
  ): Promise<ContentResult<T> | null> {
    const k = key
    if (k === null) return null
    const mine = epoch
    const own = new AbortController()
    controller = own
    let outcome: ContentResult<T>
    try {
      outcome = await request(k, own.signal)
    } catch {
      outcome = { kind: 'unavailable' }
    } finally {
      if (controller === own) controller = null
    }
    return mine === epoch ? outcome : null
  }

  function schedulePoll(inMs: number): void {
    pollTimer = setTimeout(() => {
      pollTimer = null
      void poll()
    }, inMs)
  }

  async function poll(): Promise<void> {
    const outcome = await send((k, signal) => getContent(k.poiId, k.mode, signal))
    if (outcome !== null) follow(outcome)
  }

  function follow(outcome: ReadOutcome): void {
    const step = nextPollStep(outcome, Date.now() - windowStartedAt)
    if (step.action === 'poll') {
      state.value = { kind: 'processing' }
      schedulePoll(step.inMs)
    } else if (step.action === 'repost') {
      state.value = { kind: 'takingLong' }
      // A read never parks an abandoned row, so past the ceiling only a POST can move it.
      refreshPosts = true
    } else {
      state.value = readState(outcome)
    }
  }

  /** The read on open is a single answer, not a poll: only PROCESSING (a generation already
   *  running) enters the poll window. A 503 here is shown, not polled. */
  async function open(): Promise<void> {
    if (key === null || busy()) return
    state.value = { kind: 'loading' }
    windowStartedAt = Date.now()
    const outcome = await send((k, signal) => getContent(k.poiId, k.mode, signal))
    if (outcome === null) return
    if (outcome.kind === 'content' && outcome.content.status === 'PROCESSING') {
      follow(outcome)
    } else {
      state.value = readState(outcome)
    }
  }

  /** A 503 on the POST returned the lease, so a read after it would show the state from before
   *  the POST: the answer is taken from the POST itself and nothing is polled. */
  async function generate(): Promise<void> {
    if (key === null || busy()) return
    refreshPosts = true
    state.value = { kind: 'processing' }
    windowStartedAt = Date.now()
    const outcome = await send((k, signal) => postGenerate(k.poiId, k.mode, signal))
    if (outcome === null) return
    if (outcome.kind !== 'content') {
      state.value = refusalState(outcome)
      return
    }
    const answer = outcome.content
    switch (answer.status) {
      case 'PROCESSING':
        schedulePoll(FIRST_POLL_DELAY_MS)
        return
      case 'COMPLETED':
        // The POST carries no text: one read fetches it, and a COMPLETED read stops.
        await poll()
        return
      case 'FAILED':
        state.value = { kind: 'failed', failedClass: answer.failedClass }
    }
  }

  function refresh(): void {
    void (refreshPosts ? generate() : open())
  }

  watch(
    [() => poi.value?.id, () => poi.value?.unlocked],
    ([poiId, unlocked]) => {
      stop()
      refreshPosts = false
      key = null
      if (poiId === undefined) {
        state.value = { kind: 'idle' }
        return
      }
      if (unlocked !== true) {
        state.value = { kind: 'locked' }
        return
      }
      key = { poiId, mode: CONTENT_MODE }
      void open()
    },
    { immediate: true },
  )

  return {
    state: readonly(state),
    generate: () => void generate(),
    refresh,
    stop,
  }
}
