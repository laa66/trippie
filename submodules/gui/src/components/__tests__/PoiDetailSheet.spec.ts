import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import PoiDetailSheet from '@/components/PoiDetailSheet.vue'
import { MAX_ACCURACY_METERS } from '@/lib/geo'
import type { NearbyItem } from '@/lib/nearbyApi'

afterEach(() => {
  document.body.innerHTML = ''
})

const poi = (over: Partial<NearbyItem> = {}): NearbyItem => ({
  id: 'n1',
  name: 'Pręgierz',
  category: 'attractions',
  lat: 51.1,
  lon: 17.03,
  distanceMeters: 1234,
  unlocked: false,
  ...over,
})

async function open(
  p: NearbyItem | null,
  accuracyMeters: number | null = 20,
  distanceFromFixMeters: number | null = 1234,
  requireProximity = true,
) {
  const wrapper = mount(PoiDetailSheet, {
    props: { poi: p, accuracyMeters, distanceFromFixMeters, requireProximity },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

const dialogText = () => document.querySelector('.q-dialog')?.textContent ?? ''

describe('PoiDetailSheet', () => {
  it('is closed without a POI and open with one', async () => {
    const wrapper = await open(null)
    expect(document.querySelector('.q-dialog')).toBeNull()

    await wrapper.setProps({ poi: poi() })
    await flushPromises()
    expect(document.querySelector('.q-dialog')).not.toBeNull()
    expect(dialogText()).toContain('Pręgierz')
  })

  it('shows the distance, and the category label when the slug is known', async () => {
    await open(poi())
    expect(dialogText()).toContain('1,2 km')
    expect(dialogText()).toContain('Atrakcje')
  })

  it('shows only the distance for an unknown category, never undefined', async () => {
    await open(poi({ category: 'martian_base' }), 20, 80)
    expect(dialogText()).toContain('80 m')
    expect(dialogText()).not.toContain('undefined')
  })

  // The number in the header is the distance from the user's OWN fix, which MapView measures and
  // hands in. `poi.distanceMeters` is the server's, measured from the nearby query centre, so it
  // answers a different question and must not leak into this header.
  it('shows no distance at all when there is no fix to measure from', async () => {
    await open(poi({ distanceMeters: 1234 }), null, null)
    expect(dialogText()).toContain('Atrakcje')
    expect(dialogText()).not.toContain('km')
    expect(dialogText()).not.toContain('1234')
  })

  it('ignores the server distance carried on the POI', async () => {
    await open(poi({ distanceMeters: 7777 }), 20, 42)  // 42 m from the fix, 7777 m from the map centre
    expect(dialogText()).toContain('42 m')
    expect(dialogText()).not.toContain('7777')
  })

  it('locked with good accuracy asks to come within 50 m', async () => {
    await open(poi(), 20)
    expect(dialogText()).toContain('Podejdź na 50 m, żeby odblokować')
  })

  it('degraded accuracy shows the number and outranks the locked message', async () => {
    await open(poi(), 137)
    expect(dialogText()).toContain('Dokładność ~140 m')
    expect(dialogText()).not.toContain('Podejdź na 50 m')
  })

  it('null accuracy says it is waiting for a measurement', async () => {
    await open(poi(), null)
    expect(dialogText()).toContain('Czekam na pomiar pozycji')
    expect(dialogText()).not.toContain('Podejdź na 50 m')
  })

  // `accuracy: 0` is what CDP and Playwright report by default and `useGeolocation` builds a fix
  // from it, so this is the state M3-10's tooling produces — and the unlock gate refuses it
  // unconditionally, which is why the sheet must not send the user walking.
  it('reads a zero accuracy as waiting, not as good accuracy', async () => {
    await open(poi(), 0)
    expect(dialogText()).toContain('Czekam na pomiar pozycji')
    expect(dialogText()).not.toContain('Podejdź na 50 m')
  })

  it('says nothing blocking to a user exempt from proximity', async () => {
    await open(poi(), 140, 5000, false)
    const notices = [...document.querySelectorAll('.q-dialog [role="status"]')]
    expect(notices).toHaveLength(1)
    expect(notices[0].textContent).toContain('Przewodnik dostępny wkrótce')
  })

  it('accuracy exactly at the limit counts as good', async () => {
    await open(poi(), MAX_ACCURACY_METERS)
    expect(dialogText()).toContain('Podejdź na 50 m')
    expect(dialogText()).not.toContain('Dokładność ~')
  })

  // Asserted through the notices themselves rather than a test hook: an unlocked POI has nothing
  // to explain, so the footer's note is the ONLY notice in the sheet.
  it('unlocked POI leaves the content area empty even with bad accuracy', async () => {
    await open(poi({ unlocked: true }), 500)
    const notices = [...document.querySelectorAll('.q-dialog [role="status"]')]
    expect(notices).toHaveLength(1)
    expect(notices[0].textContent).toContain('Przewodnik dostępny wkrótce')
  })

  it('keeps the guide button disabled', async () => {
    await open(poi({ unlocked: true }))
    const btn = [...document.querySelectorAll('.q-dialog .q-btn')].find(
      (b) => b.textContent?.trim() === 'Przewodnik',
    ) as HTMLButtonElement
    expect(btn).not.toBeUndefined()
    expect(btn.disabled || btn.getAttribute('aria-disabled') === 'true').toBe(true)
  })

  it('emits close from the close button', async () => {
    const wrapper = await open(poi())
    const close = [...document.querySelectorAll('.q-dialog .q-btn')].find((b) => b.textContent?.includes('Zamknij')) as HTMLElement
    close.click()
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits close on Escape', async () => {
    const wrapper = await open(poi())
    // Quasar arms on keydown and fires on keyup, both on window.
    for (const type of ['keydown', 'keyup']) {
      window.dispatchEvent(new KeyboardEvent(type, { key: 'Escape', keyCode: 27, bubbles: true }))
    }
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('emits close on a click on the backdrop', async () => {
    const wrapper = await open(poi())
    // Quasar dismisses on mousedown, not click.
    document
      .querySelector('.q-dialog__backdrop')!
      .dispatchEvent(new MouseEvent('mousedown', { button: 0, bubbles: true }))
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})
