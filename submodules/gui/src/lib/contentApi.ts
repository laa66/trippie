import { AuthApiError } from '@/lib/authClient'
import { apiFetchJson } from '@/lib/apiFetch'
import type { ContentMode } from '@/lib/settingsApi'

export const CONTENT_STATUSES = ['ABSENT', 'PROCESSING', 'COMPLETED', 'FAILED'] as const
export type ContentStatus = (typeof CONTENT_STATUSES)[number]

export const FAILED_CLASSES = ['TRANSIENT', 'PERMANENT', 'QUOTA'] as const
export type FailedClass = (typeof FAILED_CLASSES)[number]

/** GET body. `text` exists exactly on COMPLETED and `failedClass` exactly on FAILED. */
export type ContentResponse =
  | { status: 'ABSENT' | 'PROCESSING' }
  | { status: 'COMPLETED'; text: string }
  | { status: 'FAILED'; failedClass: FailedClass }

/** POST body: a status only, the text is read with GET. ABSENT is a state of the read, never an answer to a request. */
export type GenerateResponse = { status: 'PROCESSING' | 'COMPLETED' } | { status: 'FAILED'; failedClass: FailedClass }

/** The non-content outcomes are answers, not failures: 403 the POI is not unlocked for this user,
 *  503 the unlock state or the publication is down for now, 422 the mode is not served. */
export type ContentResult<T> =
  | { kind: 'content'; content: T }
  | { kind: 'locked' }
  | { kind: 'unavailable' }
  | { kind: 'unsupported' }

const CONTENT_URL = '/api/content/content'
const GENERATE_URL = '/api/content/content/generate'

function asRecord(body: unknown, what: string): Record<string, unknown> {
  if (body === null || typeof body !== 'object') {
    throw new Error(`Malformed ${what} response`)
  }
  return body as Record<string, unknown>
}

function asStatus(b: Record<string, unknown>, what: string): ContentStatus {
  if (!(CONTENT_STATUSES as readonly unknown[]).includes(b.status)) {
    throw new Error(`Malformed ${what} response`)
  }
  return b.status as ContentStatus
}

/** The server omits a field that does not apply rather than sending null, so null is a violation too. */
function requireAbsent(value: unknown, what: string): void {
  if (value !== undefined) {
    throw new Error(`Malformed ${what} response`)
  }
}

function asFailedClass(value: unknown, what: string): FailedClass {
  if (!(FAILED_CLASSES as readonly unknown[]).includes(value)) {
    throw new Error(`Malformed ${what} response`)
  }
  return value as FailedClass
}

/** Boundary check: the server body is untrusted until it has the contract shape. Mirrors
 *  settingsApi.asSettings; an unknown status must throw here, not reach the view as undefined. */
function asContentResponse(body: unknown): ContentResponse {
  const b = asRecord(body, 'content')
  const status = asStatus(b, 'content')
  switch (status) {
    case 'COMPLETED':
      if (typeof b.text !== 'string') {
        throw new Error('Malformed content response')
      }
      requireAbsent(b.failedClass, 'content')
      return { status, text: b.text }
    case 'FAILED':
      requireAbsent(b.text, 'content')
      return { status, failedClass: asFailedClass(b.failedClass, 'content') }
    default:
      requireAbsent(b.text, 'content')
      requireAbsent(b.failedClass, 'content')
      return { status }
  }
}

function asGenerateResponse(body: unknown): GenerateResponse {
  const b = asRecord(body, 'generate')
  const status = asStatus(b, 'generate')
  requireAbsent(b.text, 'generate')
  switch (status) {
    case 'ABSENT':
      throw new Error('Malformed generate response')
    case 'FAILED':
      return { status, failedClass: asFailedClass(b.failedClass, 'generate') }
    default:
      requireAbsent(b.failedClass, 'generate')
      return { status }
  }
}

async function toResult<T>(request: () => Promise<T>): Promise<ContentResult<T>> {
  try {
    return { kind: 'content', content: await request() }
  } catch (e) {
    if (e instanceof AuthApiError) {
      switch (e.status) {
        case 403:
          return { kind: 'locked' }
        case 503:
          return { kind: 'unavailable' }
        case 422:
          return { kind: 'unsupported' }
      }
    }
    throw e
  }
}

/** Reads one POI's content. Through {@link apiFetchJson}: Bearer, 401 refresh and retry, per-attempt
 *  budget. Any other non-2xx, or a body off the contract, throws. */
export function getContent(poiId: string, mode: ContentMode, signal?: AbortSignal): Promise<ContentResult<ContentResponse>> {
  const query = new URLSearchParams({ poiId, mode })
  return toResult(async () => asContentResponse(await apiFetchJson(`${CONTENT_URL}?${query.toString()}`, { signal })))
}

/** Asks for generation: PROCESSING when queued or running, COMPLETED or FAILED when the answer is final. */
export function postGenerate(poiId: string, mode: ContentMode, signal?: AbortSignal): Promise<ContentResult<GenerateResponse>> {
  return toResult(async () =>
    asGenerateResponse(
      await apiFetchJson(GENERATE_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ poiId, mode }),
        signal,
      }),
    ),
  )
}
