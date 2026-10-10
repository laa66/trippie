import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { QToggle } from 'quasar'

vi.mock('@/lib/settingsApi', () => ({ getSettings: vi.fn(() => new Promise(() => {})), putSettings: vi.fn() }))

import CategoryFilter from '@/components/CategoryFilter.vue'
import { CATEGORY_LABELS, CATEGORY_SLUGS, useCategorySelection } from '@/composables/useCategorySelection'

const { selected } = useCategorySelection()

afterEach(() => {
  selected.value = [...CATEGORY_SLUGS]
  document.body.innerHTML = ''
})

/**
 * The port swapped this component's public contract (`is-open` + `update:isOpen` -> `v-model`)
 * and its internals (ion-modal/ion-toggle -> QDialog/QToggle), while the M1 criterion it serves —
 * unticking a category drops it from the map without a reload — had no test at all. QDialog
 * teleports to <body>, so the rendered assertions go through the document.
 */
describe('CategoryFilter', () => {
  it('shows one labelled toggle per category while the model is true', async () => {
    mount(CategoryFilter, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()

    const dialog = document.querySelector('.q-dialog')
    expect(dialog).not.toBeNull()
    expect(dialog!.querySelectorAll('.q-toggle')).toHaveLength(CATEGORY_SLUGS.length)
    expect(dialog!.textContent).toContain(CATEGORY_LABELS.public_art)
    expect(dialog!.textContent).toContain(CATEGORY_LABELS.attractions)
  })

  it('stays closed while the model is false', async () => {
    mount(CategoryFilter, { props: { modelValue: false }, attachTo: document.body })
    await flushPromises()

    expect(document.querySelector('.q-dialog')).toBeNull()
  })

  it('unticking a category removes it from the shared selection', async () => {
    const wrapper = mount(CategoryFilter, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()
    expect(selected.value).toHaveLength(CATEGORY_SLUGS.length)

    await wrapper.findAllComponents(QToggle)[0].trigger('click')
    await flushPromises()

    // The map re-queries off this ref, so dropping the slug here is the whole M1 criterion.
    expect(selected.value).not.toContain(CATEGORY_SLUGS[0])
    expect(selected.value).toHaveLength(CATEGORY_SLUGS.length - 1)
  })

  it('closes through the model, not by hiding itself', async () => {
    const wrapper = mount(CategoryFilter, { props: { modelValue: true }, attachTo: document.body })
    await flushPromises()

    const close = [...document.querySelectorAll('.q-dialog .q-btn')].find((b) =>
      b.textContent?.includes('Zamknij'),
    ) as HTMLElement | undefined
    expect(close).toBeDefined()
    close!.click()
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })
})
