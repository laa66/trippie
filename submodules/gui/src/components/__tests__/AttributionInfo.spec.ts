import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { QMenu } from 'quasar'
import AttributionInfo from '@/components/AttributionInfo.vue'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('AttributionInfo', () => {
  it('renders a trigger button carrying the attribution menu', () => {
    const wrapper = mount(AttributionInfo)

    const trigger = wrapper.find('button')
    expect(trigger.exists()).toBe(true)
    expect(trigger.attributes('aria-label')).toBe('Informacje o źródle danych')
    expect(wrapper.findComponent(QMenu).exists()).toBe(true)
  })

  // The ODbL wording is the legal requirement from M1-12, so the assertion targets the portal
  // QMenu teleports into <body> — never `document.body.textContent`, which also contains the
  // mounted wrapper and would stay green with the paragraph moved outside the menu entirely.
  it('shows the ODbL attribution inside the menu, and only once opened', async () => {
    const wrapper = mount(AttributionInfo, { attachTo: document.body })
    try {
      expect(document.querySelector('.q-menu')).toBeNull()

      await wrapper.find('button').trigger('click')
      await flushPromises()

      const menu = document.querySelector('.q-menu')
      expect(menu).not.toBeNull()
      expect(menu!.textContent).toContain('© OpenStreetMap contributors')
      expect(menu!.textContent).toContain('ODbL')
    } finally {
      wrapper.unmount()
    }
  })

  it('states POI data is © OpenStreetMap contributors under ODbL', async () => {
    const source = await import('@/components/AttributionInfo.vue?raw')
    expect(source.default).toContain('© OpenStreetMap contributors')
    expect(source.default).toContain('ODbL')
  })
})
