import { describe, expect, it } from 'vitest'
import { formatDistance, poiContentMessage } from '@/lib/poiDetail'

const WAITING = 'Czekam na pomiar pozycji'
const COME_CLOSER = 'Podejdź na 50 m, żeby odblokować'

describe('formatDistance', () => {
  it('rounds to whole metres below 1000', () => {
    expect(formatDistance(0)).toBe('0 m')
    expect(formatDistance(42.4)).toBe('42 m')
    expect(formatDistance(999.4)).toBe('999 m')
  })

  it('switches to kilometres at 1000 with a decimal comma', () => {
    expect(formatDistance(1000)).toBe('1,0 km')
    expect(formatDistance(1234)).toBe('1,2 km')
    expect(formatDistance(12500)).toBe('12,5 km')
  })

  // The branch reads the ROUNDED value: on the raw one this range rendered as "1000 m", which sits
  // next to "1,0 km" in the same component and reads as a bug.
  it('never renders 1000 m', () => {
    expect(formatDistance(999.5)).toBe('1,0 km')
    expect(formatDistance(999.99)).toBe('1,0 km')
  })
})

describe('poiContentMessage', () => {
  it('is empty for an unlocked POI whatever the accuracy', () => {
    expect(poiContentMessage(true, 500, true)).toBeNull()
    expect(poiContentMessage(true, null, true)).toBeNull()
    expect(poiContentMessage(true, null, false)).toBeNull()
  })

  it('announces the accuracy in tens of metres, not to the metre', () => {
    expect(poiContentMessage(false, 137, true)?.text).toBe(
      'Dokładność ~140 m — podejdź bliżej lub poczekaj na sygnał',
    )
    expect(poiContentMessage(false, 141, true)?.text).toContain('~140 m')
  })

  /**
   * The gate in `proximity.ts` rejects a non-positive accuracy UNCONDITIONALLY, so a sheet that
   * said "come within 50 m" here would be telling the user to do something that cannot work. And
   * `accuracy: 0` is what CDP and Playwright report by default, which is the M3-10 tooling path.
   */
  it('reads a useless measurement as waiting, never as good accuracy', () => {
    for (const accuracy of [null, 0, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
      const message = poiContentMessage(false, accuracy, true)
      expect(message?.text, `accuracy ${String(accuracy)}`).toBe(WAITING)
      expect(message?.variant, `accuracy ${String(accuracy)}`).toBe('info')
    }
  })

  // An exempt user has neither a radius nor a ceiling to meet: both blocking messages would be
  // false, and the unlock rides their next fix.
  it('says nothing blocking to a user exempt from proximity', () => {
    expect(poiContentMessage(false, 20, false)).toBeNull()
    expect(poiContentMessage(false, 140, false)).toBeNull()
  })

  it('still waits for a measurement when exempt, because even an exempt unlock needs a fix', () => {
    expect(poiContentMessage(false, null, false)?.text).toBe(WAITING)
    expect(poiContentMessage(false, 0, false)?.text).toBe(WAITING)
  })

  it('treats exactly 100 m as good accuracy and 101 m as degraded', () => {
    expect(poiContentMessage(false, 100, true)?.text).toBe(COME_CLOSER)
    expect(poiContentMessage(false, 101, true)?.text).toContain('Dokładność ~100 m')
  })

  it('warns only on degraded accuracy; waiting and the radius hint are informational', () => {
    expect(poiContentMessage(false, 140, true)?.variant).toBe('warning')
    expect(poiContentMessage(false, 20, true)?.variant).toBe('info')
    expect(poiContentMessage(false, null, true)?.variant).toBe('info')
  })
})
