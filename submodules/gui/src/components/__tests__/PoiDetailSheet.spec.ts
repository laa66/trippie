import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

import PoiDetailSheet from '@/components/PoiDetailSheet.vue'
import { MAX_ACCURACY_METERS } from '@/lib/geo'
import type { ContentState } from '@/composables/useContentGeneration'
import type { NearbyItem } from '@/lib/nearbyApi'

afterEach(() => {
  vi.restoreAllMocks()
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
  content: ContentState = p === null ? { kind: 'idle' } : p.unlocked ? { kind: 'absent' } : { kind: 'locked' },
) {
  const wrapper = mount(PoiDetailSheet, {
    props: { poi: p, accuracyMeters, distanceFromFixMeters, requireProximity, content },
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
    expect(document.querySelectorAll('.q-dialog [role="status"]')).toHaveLength(0)
    expect(document.querySelectorAll('.q-dialog [role="alert"]')).toHaveLength(0)
  })

  it('accuracy exactly at the limit counts as good', async () => {
    await open(poi(), MAX_ACCURACY_METERS)
    expect(dialogText()).toContain('Podejdź na 50 m')
    expect(dialogText()).not.toContain('Dokładność ~')
  })

  it('unlocked POI with nothing generated shows no notice even with bad accuracy', async () => {
    await open(poi({ unlocked: true }), 500)
    expect(document.querySelectorAll('.q-dialog [role="status"]')).toHaveLength(0)
    expect(dialogText()).not.toContain('wkrótce')
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

const unlocked = poi({ unlocked: true })

const buttons = () => [...document.querySelectorAll<HTMLButtonElement>('.q-dialog .q-btn')]
const buttonByLabel = (label: string) => buttons().find((b) => b.textContent?.trim() === label)
const isDisabled = (b: HTMLButtonElement | undefined) => b!.disabled || b!.getAttribute('aria-disabled') === 'true'
const liveText = () =>
  [...document.querySelectorAll('.q-dialog [role="status"], .q-dialog [role="alert"], .q-dialog [aria-live]')]
    .map((e) => e.textContent)
    .join(' ')

async function completeAfterAction(text: string, p: NearbyItem = unlocked) {
  const wrapper = await open(p, 20, 10, true, { kind: 'absent' })
  buttonByLabel('Przewodnik')!.focus()
  buttonByLabel('Przewodnik')!.click()
  await wrapper.setProps({ content: { kind: 'processing' } })
  await flushPromises()
  await wrapper.setProps({ content: { kind: 'completed', text } })
  await flushPromises()
  return wrapper
}

describe('PoiDetailSheet content states', () => {
  it('locked: shows the lock message, the guide button is disabled and emits nothing', async () => {
    const wrapper = await open(poi(), 20)
    expect(dialogText()).toContain('Podejdź na 50 m, żeby odblokować')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    buttonByLabel('Przewodnik')!.click()
    await flushPromises()
    expect(wrapper.emitted('generate')).toBeUndefined()
  })

  it('loading: shows progress and the guide button is disabled', async () => {
    await open(unlocked, 20, 10, true, { kind: 'loading' })
    expect(document.querySelector('.q-dialog [role="status"]')?.textContent).toContain('Sprawdzam przewodnik')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
  })

  it('absent: the guide button is active and emits generate, no "coming soon" notice', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'absent' })
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(false)
    expect(dialogText()).not.toContain('wkrótce')
    buttonByLabel('Przewodnik')!.click()
    await flushPromises()
    expect(wrapper.emitted('generate')).toHaveLength(1)
    expect(wrapper.emitted('refresh')).toBeUndefined()
  })

  it('processing: shows progress and the guide button is disabled', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'processing' })
    expect(document.querySelector('.q-dialog [role="status"]')?.textContent).toContain('Przygotowuję przewodnik')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    buttonByLabel('Przewodnik')!.click()
    await flushPromises()
    expect(wrapper.emitted('generate')).toBeUndefined()
  })

  it('completed: shows the text, the button is disabled and there is nothing to refresh', async () => {
    await open(unlocked, 20, 10, true, { kind: 'completed', text: 'Dawno temu na rynku stał pręgierz.' })
    expect(dialogText()).toContain('Dawno temu na rynku stał pręgierz.')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    expect(buttonByLabel('Odśwież')).toBeUndefined()
    expect(buttonByLabel('Spróbuj ponownie')).toBeUndefined()
    expect(buttonByLabel('Ponów')).toBeUndefined()
  })

  it('completed: the text is not inside any live region', async () => {
    await open(unlocked, 20, 10, true, { kind: 'completed', text: 'Dawno temu na rynku stał pręgierz.' })
    expect(liveText()).not.toContain('pręgierz')
    const text = [...document.querySelectorAll('.q-dialog *')].find((e) => e.children.length === 0 && e.textContent?.includes('pręgierz'))
    expect(text).not.toBeUndefined()
    expect(text!.closest('[role="status"], [role="alert"], [aria-live]')).toBeNull()
  })

  it('completed: renders model text as text, never as markup', async () => {
    await open(unlocked, 20, 10, true, { kind: 'completed', text: '<img src=x onerror=alert(1)><b>x</b>' })
    expect(document.querySelector('.q-dialog img')).toBeNull()
    expect(document.querySelector('.q-dialog b')).toBeNull()
    expect(dialogText()).toContain('<img src=x onerror=alert(1)><b>x</b>')
  })

  it('completed: the text sits in a bounded, scrollable container', async () => {
    await open(unlocked, 20, 10, true, { kind: 'completed', text: 'x' })
    const box = document.querySelector('.q-dialog [role="region"]')!
    expect(box.className).toContain('max-h-60')
    expect(box.className).toContain('overflow-y-auto')
  })

  it('takingLong: says it takes longer and refresh emits refresh', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'takingLong' })
    expect(dialogText()).toContain('trwa dłużej niż zwykle')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    buttonByLabel('Odśwież')!.click()
    await flushPromises()
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('generate')).toBeUndefined()
  })

  it('locked race (403 on an unlocked POI): asks to come closer, button disabled, no retry', async () => {
    await open(unlocked, 20, 10, true, { kind: 'locked' })
    expect(dialogText()).toContain('Podejdź bliżej')
    expect(dialogText()).not.toContain('Podejdź na 50 m')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    expect(buttonByLabel('Spróbuj ponownie')).toBeUndefined()
  })

  it.each(['unavailable', 'unsupported'] as const)('%s: says try again and the retry emits refresh', async (kind) => {
    const wrapper = await open(unlocked, 20, 10, true, { kind })
    expect(dialogText()).toContain('chwilowo niedostępny')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    buttonByLabel('Spróbuj ponownie')!.click()
    await flushPromises()
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.emitted('generate')).toBeUndefined()
  })

  it('unsupported reads exactly as unavailable', async () => {
    await open(unlocked, 20, 10, true, { kind: 'unavailable' })
    const unavailable = dialogText()
    document.body.innerHTML = ''
    await open(unlocked, 20, 10, true, { kind: 'unsupported' })
    expect(dialogText()).toBe(unavailable)
  })

  it('failed TRANSIENT: "Ponów" is active and emits generate, no refresh control', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'failed', failedClass: 'TRANSIENT' })
    expect(dialogText()).toContain('Nie udało się wygenerować')
    expect(buttonByLabel('Przewodnik')).toBeUndefined()
    expect(isDisabled(buttonByLabel('Ponów'))).toBe(false)
    buttonByLabel('Ponów')!.click()
    await flushPromises()
    expect(wrapper.emitted('generate')).toHaveLength(1)
    expect(wrapper.emitted('refresh')).toBeUndefined()
    expect(buttonByLabel('Odśwież')).toBeUndefined()
  })

  it('failed PERMANENT: definitive sentence, button disabled, nothing to retry or refresh', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'failed', failedClass: 'PERMANENT' })
    expect(dialogText()).toContain('Ponowienie nic nie zmieni')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(true)
    buttonByLabel('Przewodnik')!.click()
    await flushPromises()
    expect(wrapper.emitted('generate')).toBeUndefined()
    expect(buttonByLabel('Ponów')).toBeUndefined()
    expect(buttonByLabel('Odśwież')).toBeUndefined()
    expect(buttonByLabel('Spróbuj ponownie')).toBeUndefined()
  })

  it('failed QUOTA: its own sentence about the daily limit, the only one that says tomorrow', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'failed', failedClass: 'QUOTA' })
    expect(dialogText()).toContain('Dzienny limit')
    expect(dialogText()).toContain('jutro')
    expect(isDisabled(buttonByLabel('Przewodnik'))).toBe(false)
    buttonByLabel('Przewodnik')!.click()
    await flushPromises()
    expect(wrapper.emitted('generate')).toHaveLength(1)
  })

  it('the three refusals and the three failure classes are all different sentences', async () => {
    const states: ContentState[] = [
      { kind: 'locked' },
      { kind: 'unavailable' },
      { kind: 'failed', failedClass: 'TRANSIENT' },
      { kind: 'failed', failedClass: 'PERMANENT' },
      { kind: 'failed', failedClass: 'QUOTA' },
    ]
    const texts: string[] = []
    for (const content of states) {
      document.body.innerHTML = ''
      await open(unlocked, 20, 10, true, content)
      texts.push(liveText().trim())
    }
    expect(texts.every((t) => t.length > 0)).toBe(true)
    expect(new Set(texts).size).toBe(states.length)
    expect(texts.filter((t) => t.includes('jutro'))).toHaveLength(1)
  })

  it('a failure is announced assertively, whatever the class, and progress politely', async () => {
    for (const failedClass of ['TRANSIENT', 'PERMANENT', 'QUOTA'] as const) {
      document.body.innerHTML = ''
      await open(unlocked, 20, 10, true, { kind: 'failed', failedClass })
      expect(document.querySelector('.q-dialog [role="alert"]')?.textContent).toContain(
        failedClass === 'QUOTA' ? 'Dzienny limit' : 'Nie udało się wygenerować',
      )
      expect(document.querySelector('.q-dialog [role="status"]')).toBeNull()
    }
    document.body.innerHTML = ''
    await open(unlocked, 20, 10, true, { kind: 'processing' })
    expect(document.querySelector('.q-dialog [role="status"]')).not.toBeNull()
    expect(document.querySelector('.q-dialog [role="alert"]')).toBeNull()
  })

  it('completed: the text region is focusable, named and keeps the paragraph breaks', async () => {
    await open(unlocked, 20, 10, true, { kind: 'completed', text: 'a\n\nb' })
    const region = document.querySelector('.q-dialog [role="region"]')!
    expect(region.getAttribute('tabindex')).toBe('0')
    expect(region.getAttribute('aria-label')).toBe('Przewodnik')
    expect(region.className).toContain('whitespace-pre-line')
  })

  it('completed after an action: announces only "Przewodnik gotowy.", never the guide text', async () => {
    await completeAfterAction('Dawno temu na rynku stał pręgierz.')
    const statuses = [...document.querySelectorAll('.q-dialog [role="status"]')]
    expect(statuses).toHaveLength(1)
    expect(statuses[0].textContent?.trim()).toBe('Przewodnik gotowy.')
    expect(statuses[0].textContent).not.toContain('pręgierz')
  })

  it('does not announce completion for any other state', async () => {
    for (const content of [{ kind: 'absent' }, { kind: 'processing' }, { kind: 'takingLong' }] as ContentState[]) {
      document.body.innerHTML = ''
      await open(unlocked, 20, 10, true, content)
      expect(liveText()).not.toContain('gotowy')
    }
  })
})

describe('PoiDetailSheet focus', () => {
  const focusIsSafe = () => {
    const active = document.activeElement as HTMLElement
    expect(active).not.toBe(document.body)
    expect(document.querySelector('.q-dialog')!.contains(active)).toBe(true)
    expect((active as HTMLButtonElement).disabled).not.toBe(true)
    expect(active.getAttribute('aria-disabled')).not.toBe('true')
  }

  it.each([
    ['Przewodnik', { kind: 'absent' }, { kind: 'processing' }],
    ['Ponów', { kind: 'failed', failedClass: 'TRANSIENT' }, { kind: 'processing' }],
    ['Odśwież', { kind: 'takingLong' }, { kind: 'processing' }],
    ['Spróbuj ponownie', { kind: 'unavailable' }, { kind: 'loading' }],
  ] as [string, ContentState, ContentState][])(
    'after "%s" the focus stays inside the sheet, not on the body',
    async (label, before, after) => {
      const wrapper = await open(unlocked, 20, 10, true, before)
      const btn = buttonByLabel(label)!
      btn.focus()
      expect(document.activeElement).toBe(btn)
      btn.click()
      await wrapper.setProps({ content: after })
      await flushPromises()
      focusIsSafe()
    },
  )

  it('a retry that fails again leaves the focus inside the sheet', async () => {
    const wrapper = await open(unlocked, 20, 10, true, { kind: 'unavailable' })
    buttonByLabel('Spróbuj ponownie')!.click()
    await wrapper.setProps({ content: { kind: 'loading' } })
    await flushPromises()
    await wrapper.setProps({ content: { kind: 'unavailable' } })
    await flushPromises()
    focusIsSafe()
  })

  it('the finished text takes the focus after an action', async () => {
    await completeAfterAction('Tekst.')
    expect(document.activeElement).toBe(document.querySelector('.q-dialog [role="region"]'))
  })

  it('a plain open that ends in cached text moves no focus and announces nothing', async () => {
    const wrapper = await open(null)
    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    await wrapper.setProps({ poi: unlocked, content: { kind: 'loading' } })
    await flushPromises()
    await wrapper.setProps({ content: { kind: 'completed', text: 'Z cache.' } })
    await flushPromises()
    expect(document.activeElement).not.toBe(document.querySelector('.q-dialog [role="region"]'))
    expect(document.querySelector('.q-dialog [role="status"]')).toBeNull()
    expect(dialogText()).toContain('Z cache.')
  })

  it('an action on one POI does not carry over to the next', async () => {
    const wrapper = await completeAfterAction('Pierwszy.')
    const other = poi({ id: 'n2', name: 'Ratusz', unlocked: true })
    await wrapper.setProps({ poi: other, content: { kind: 'loading' } })
    await flushPromises()
    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    await wrapper.setProps({ content: { kind: 'completed', text: 'Drugi.' } })
    await flushPromises()
    expect(document.activeElement).toBe(outside)
    expect(document.querySelector('.q-dialog [role="status"]')).toBeNull()
  })

  it('closing the sheet from completed does not touch the focus', async () => {
    const wrapper = await completeAfterAction('Tekst.')
    const focus = vi.spyOn(HTMLElement.prototype, 'focus')
    await wrapper.setProps({ poi: null, content: { kind: 'idle' } })
    await flushPromises()
    expect(focus).not.toHaveBeenCalled()
  })
})
