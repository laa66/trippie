import type { ContentResult, ContentStatus } from '@/lib/contentApi'

export const POLL_INTERVAL_MS = 2_000
export const POLL_CEILING_MS = 60_000

export type PollStep = { action: 'poll'; inMs: number } | { action: 'stop' } | { action: 'repost' }

/**
 * What to do after one GET or POST outcome, `elapsedMs` into the poll window. Reading never parks an
 * abandoned row (only a POST does), so a row still PROCESSING at the ceiling is re-requested, not
 * polled forever. An unavailable answer is about spatial or the broker, so it keeps polling under the
 * same ceiling; a locked or unsupported one cannot change within a session, so it stops.
 *
 * `repost` is not an automatic action: it means the user-triggered refresh must POST, not GET. No
 * request goes out after the ceiling without a user action.
 */
export function nextPollStep(outcome: ContentResult<{ status: ContentStatus }>, elapsedMs: number): PollStep {
  if (outcome.kind === 'locked' || outcome.kind === 'unsupported') {
    return { action: 'stop' }
  }
  if (outcome.kind === 'content' && outcome.content.status !== 'PROCESSING') {
    return { action: 'stop' }
  }
  return elapsedMs >= POLL_CEILING_MS ? { action: 'repost' } : { action: 'poll', inMs: Math.min(POLL_INTERVAL_MS, POLL_CEILING_MS - elapsedMs) }
}
