/**
 * Runs `leg` with a signal that aborts after `timeoutMs`, or as soon as `callerSignal` aborts —
 * carrying the caller's own reason, so a cancellation stays distinguishable from a timeout
 * (`AbortError` vs `TimeoutError`, which is what useNearbyPois discriminates on).
 *
 * The signal is handed to the leg rather than a bounded fetch returned, so a body read done inside
 * the leg stays within the window too: a body that never streams hangs the caller exactly like
 * headers that never arrive.
 *
 * The timer AND the caller-signal listener are released on EVERY exit, not only on an abort:
 * `callerSignal` may be long-lived (one per session in useCategorySelection), and a listener left
 * behind per completed call accumulates on it until that session ends.
 *
 * Not AbortSignal.any / AbortSignal.timeout: both need Safari 17.4, and the Capacitor wrapper runs
 * on the OS WebView, so the floor is the OS browser's.
 */
export async function bounded<T>(
  timeoutMs: number,
  leg: (signal: AbortSignal) => Promise<T>,
  callerSignal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(new DOMException('timeout', 'TimeoutError')), timeoutMs)
  const relay = (): void => controller.abort(callerSignal?.reason)
  // An ALREADY-aborted signal never fires 'abort' again, and callers do pass one: apiFetch arms a
  // fresh window for the post-refresh retry, by which time the caller may have cancelled.
  if (callerSignal?.aborted) {
    relay()
  } else {
    callerSignal?.addEventListener('abort', relay, { once: true })
  }
  try {
    return await leg(controller.signal)
  } finally {
    clearTimeout(timer)
    callerSignal?.removeEventListener('abort', relay)
  }
}
