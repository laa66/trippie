import type { ContentState } from '@/composables/useContentGeneration'
import { MAX_ACCURACY_METERS, UNLOCK_RADIUS_METERS } from '@/lib/geo'

/** Under 1000 m whole metres, from 1000 m kilometres with one decimal and a Polish decimal comma. */
export function formatDistance(meters: number): string {
  // Rounded BEFORE the branch: on the raw value, 999.5 renders as "1000 m" next to "1,0 km".
  const rounded = Math.round(meters)
  if (rounded < 1000) return `${rounded} m`
  return `${(meters / 1000).toFixed(1).replace('.', ',')} km`
}

/**
 * Accuracy is announced in tens of metres. Two reasons, both measured: the raw value moves with
 * every `watchPosition` fix (~1 Hz), and the message lives in an `aria-live` region, so a
 * metre-precise number re-announces the whole sentence once a second for as long as the signal
 * stays poor — which is exactly the state this message exists for. And a metre of precision is a
 * fiction anyway when the error bar is 140 m.
 */
function announcedAccuracy(meters: number): number {
  return Math.round(meters / 10) * 10
}

export interface PoiContentMessage {
  text: string
  variant: 'info' | 'warning'
}

/**
 * What the sheet's content area says. `null` means the area stays an empty slot reserved for the
 * M4 guide.
 *
 * The message has to be a function of the SAME inputs as the gate it explains
 * ({@link evaluateUnlockCandidates}), or it tells the user to do something that will not work:
 *
 * - No usable measurement — `null`, or a non-finite/non-positive accuracy — outranks everything,
 *   because the client sends nothing without one. `<= 0` is not hypothetical: CDP's
 *   `Emulation.setGeolocationOverride` and Playwright's `page.setGeolocation` report `accuracy: 0`
 *   by default, and `useGeolocation` builds a fix from it (0 is finite), so this is the state the
 *   M3-10 verification tooling actually produces. It reads as "waiting", not as a fault: it is the
 *   normal first seconds of every session.
 * - An EXEMPT user (`requireProximity === false`) has no distance and no accuracy ceiling to meet,
 *   so neither blocking message is true for them — the unlock rides their next fix, and the sheet
 *   says nothing rather than something false.
 * - Degraded accuracy outranks plain "locked" on the proximity path: above the ceiling the gate
 *   refuses REGARDLESS of distance, so "come within 50 m" would send the user on a walk that
 *   changes nothing — and at that error bar there is no telling whether they are inside already.
 */
export function poiContentMessage(
  unlocked: boolean,
  accuracyMeters: number | null,
  requireProximity: boolean,
): PoiContentMessage | null {
  if (unlocked) return null

  // Mirrors the unconditional contract gate in `proximity.ts`, NaN included.
  if (accuracyMeters === null || !(Number.isFinite(accuracyMeters) && accuracyMeters > 0)) {
    return { text: 'Czekam na pomiar pozycji', variant: 'info' }
  }

  if (!requireProximity) return null

  if (accuracyMeters > MAX_ACCURACY_METERS) {
    return {
      text: `Dokładność ~${announcedAccuracy(accuracyMeters)} m — podejdź bliżej lub poczekaj na sygnał`,
      variant: 'warning',
    }
  }
  return { text: `Podejdź na ${UNLOCK_RADIUS_METERS} m, żeby odblokować`, variant: 'info' }
}

export interface ContentNotice {
  text: string
  variant: 'info' | 'warning' | 'danger'
  role: 'status' | 'alert'
  /** The control the notice carries: a retry or a refresh, both emitted as `refresh`. */
  action: { label: string } | null
}

const UNAVAILABLE: ContentNotice = {
  text: 'Przewodnik jest chwilowo niedostępny.',
  variant: 'warning',
  role: 'status',
  action: { label: 'Spróbuj ponownie' },
}

/**
 * The generation outcomes that are neither text nor progress. Three refusals say three different
 * things, because each asks the user for something different: the 403 race (come closer), the
 * 503 and 422 (try again, same sentence for both), and a failed generation, which splits by
 * class — only a quota failure may say "tomorrow", and only a permanent one may say "never".
 *
 * `unlockedPerNearby` tells a 403 on a POI that `nearby` reported as unlocked from a plain locked
 * one: the lock message of {@link poiContentMessage} covers the second, the first has nothing to
 * explain it and gets its own sentence. A locked POI returns `null`, and so does every state that
 * is progress, text or silence.
 */
export function contentNotice(content: ContentState, unlockedPerNearby: boolean): ContentNotice | null {
  switch (content.kind) {
    case 'locked':
      return unlockedPerNearby
        ? { text: 'To miejsce nie jest jeszcze odblokowane. Podejdź bliżej.', variant: 'warning', role: 'status', action: null }
        : null
    case 'unavailable':
    case 'unsupported':
      return UNAVAILABLE
    case 'takingLong':
      return {
        text: 'Generowanie trwa dłużej niż zwykle.',
        variant: 'info',
        role: 'status',
        action: { label: 'Odśwież' },
      }
    case 'failed':
      switch (content.failedClass) {
        case 'TRANSIENT':
          return { text: 'Nie udało się wygenerować przewodnika.', variant: 'danger', role: 'alert', action: null }
        case 'PERMANENT':
          return {
            text: 'Nie udało się wygenerować przewodnika dla tego miejsca. Ponowienie nic nie zmieni.',
            variant: 'danger',
            role: 'alert',
            action: null,
          }
        case 'QUOTA':
          return {
            text: 'Dzienny limit generowania przewodników został wyczerpany. Spróbuj jutro.',
            variant: 'warning',
            role: 'alert',
            action: null,
          }
      }
    default:
      return null
  }
}

/** The status line next to the spinner, `null` when nothing is in progress. */
export function contentProgressText(content: ContentState): string | null {
  switch (content.kind) {
    case 'loading':
      return 'Sprawdzam przewodnik…'
    case 'processing':
      return 'Przygotowuję przewodnik…'
    default:
      return null
  }
}

/**
 * The footer button. Enabled only where a POST is the right next step: nothing generated yet, a
 * transient failure ("Ponów") and a quota failure, which stays enabled on purpose — the row keeps
 * the failure until the next attempt, so a disabled button would lock the POI out for good,
 * tomorrow included. The unavailable, taking-long and refusal states retry through the notice
 * (`refresh`), not through this button.
 */
export function generateButton(content: ContentState): { label: string; enabled: boolean } {
  switch (content.kind) {
    case 'absent':
      return { label: 'Przewodnik', enabled: true }
    case 'failed':
      return content.failedClass === 'TRANSIENT'
        ? { label: 'Ponów', enabled: true }
        : { label: 'Przewodnik', enabled: content.failedClass === 'QUOTA' }
    default:
      return { label: 'Przewodnik', enabled: false }
  }
}
