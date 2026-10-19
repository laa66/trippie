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
