import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AttributionInfo from '@/components/AttributionInfo.vue'

// ion-popover teleports its slotted content into an Ionic overlay at runtime,
// which jsdom does not simulate — so the ODbL wording is asserted from the
// component's raw markup instead of the rendered DOM.
describe('AttributionInfo', () => {
  it('renders a trigger button wired to the popover', () => {
    const wrapper = mount(AttributionInfo)

    expect(wrapper.find('#attribution-info-trigger').exists()).toBe(true)
    expect(wrapper.find('ion-popover').exists()).toBe(true)
  })

  it('states POI data is © OpenStreetMap contributors under ODbL', async () => {
    const source = await import('@/components/AttributionInfo.vue?raw')
    expect(source.default).toContain('© OpenStreetMap contributors')
    expect(source.default).toContain('ODbL')
  })
})
