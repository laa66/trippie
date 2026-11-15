import { describe, expect, it } from 'vitest'
import type { ContentResult, ContentStatus } from '@/lib/contentApi'
import { nextPollStep, POLL_CEILING_MS, POLL_INTERVAL_MS } from '@/lib/contentPoll'

const content = (status: ContentStatus): ContentResult<{ status: ContentStatus }> => ({ kind: 'content', content: { status } })

describe('poll constants', () => {
  it('poll every 2 s under a 60 s ceiling', () => {
    expect(POLL_INTERVAL_MS).toBe(2_000)
    expect(POLL_CEILING_MS).toBe(60_000)
  })
})

describe('nextPollStep', () => {
  const stop = { action: 'stop' }
  const poll = { action: 'poll', inMs: POLL_INTERVAL_MS }
  const repost = { action: 'repost' }

  it.each([
    ['COMPLETED', content('COMPLETED'), 0, stop],
    ['FAILED', content('FAILED'), 0, stop],
    ['ABSENT', content('ABSENT'), 0, stop],
    ['PROCESSING', content('PROCESSING'), 0, poll],
    ['locked', { kind: 'locked' } as const, 0, stop],
    ['unavailable', { kind: 'unavailable' } as const, 0, poll],
    ['unsupported', { kind: 'unsupported' } as const, 0, stop],
  ])('%s at the start of the window', (_name, outcome, elapsedMs, expected) => {
    expect(nextPollStep(outcome, elapsedMs)).toEqual(expected)
  })

  it.each([
    ['PROCESSING', content('PROCESSING'), 0, 2_000],
    ['PROCESSING', content('PROCESSING'), 57_999, 2_000],
    ['PROCESSING', content('PROCESSING'), 58_000, 2_000],
    ['PROCESSING', content('PROCESSING'), 58_001, 1_999],
    ['PROCESSING', content('PROCESSING'), 59_500, 500],
    ['unavailable', { kind: 'unavailable' } as const, 59_500, 500],
  ])('%s at %i ms polls in %i ms: the last poll lands on the ceiling, not past it', (_name, outcome, elapsedMs, inMs) => {
    expect(nextPollStep(outcome, elapsedMs)).toEqual({ action: 'poll', inMs })
  })

  it.each([
    ['PROCESSING', content('PROCESSING')],
    ['unavailable', { kind: 'unavailable' } as const],
  ])('%s polls up to 1 ms before the ceiling and re-posts from the ceiling on', (_name, outcome) => {
    expect(nextPollStep(outcome, POLL_CEILING_MS - 1)).toEqual({ action: 'poll', inMs: 1 })
    expect(nextPollStep(outcome, POLL_CEILING_MS)).toEqual(repost)
    expect(nextPollStep(outcome, POLL_CEILING_MS + 1)).toEqual(repost)
    expect(nextPollStep(outcome, 10 * POLL_CEILING_MS)).toEqual(repost)
  })

  it.each([
    ['COMPLETED', content('COMPLETED')],
    ['FAILED', content('FAILED')],
    ['ABSENT', content('ABSENT')],
    ['locked', { kind: 'locked' } as const],
    ['unsupported', { kind: 'unsupported' } as const],
  ])('%s stops even past the ceiling: only an unfinished wait re-posts', (_name, outcome) => {
    expect(nextPollStep(outcome, POLL_CEILING_MS)).toEqual(stop)
    expect(nextPollStep(outcome, 10 * POLL_CEILING_MS)).toEqual(stop)
  })
})
