import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { IonRadioGroup, IonToggle } from '@ionic/vue'

vi.mock('@/lib/settingsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/settingsApi')>()),
  getSettings: vi.fn(),
  putSettings: vi.fn(),
}))

import { getSettings, putSettings } from '@/lib/settingsApi'
import { setAccessToken, clearSession } from '@/lib/authStore'
import SettingsView from '@/views/SettingsView.vue'

beforeEach(() => {
  vi.clearAllMocks()
  clearSession()
})

describe('SettingsView', () => {
  it('shows server settings and writes a category toggle through PUT', async () => {
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'AUDIO', selectedCategories: ['museums'] })
    vi.mocked(putSettings).mockImplementation(async (s) => s)
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)

    expect(wrapper.findComponent(IonRadioGroup).props('value')).toBe('AUDIO')
    const toggles = wrapper.findAllComponents(IonToggle)
    expect(toggles).toHaveLength(8)
    expect(toggles.filter((t) => t.props('checked')).map((t) => t.text())).toEqual(['Muzea'])

    toggles[1].vm.$emit('ionChange')
    await flushPromises()
    expect(putSettings).toHaveBeenCalledWith({ defaultContentMode: 'AUDIO', selectedCategories: ['museums', 'monuments'] })
  })

  it('a changed content mode is PUT; an unchanged one is not', async () => {
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['museums'] })
    vi.mocked(putSettings).mockImplementation(async (s) => s)
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)
    const group = wrapper.findComponent(IonRadioGroup)

    group.vm.$emit('ionChange', { detail: { value: 'BOTH' } })
    await flushPromises()
    expect(putSettings).not.toHaveBeenCalled()

    group.vm.$emit('ionChange', { detail: { value: 'TEXT' } })
    await flushPromises()
    expect(putSettings).toHaveBeenCalledWith({ defaultContentMode: 'TEXT', selectedCategories: ['museums'] })
  })

  it('renders the revert error as text after a failed save', async () => {
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['museums'] })
    vi.mocked(putSettings).mockRejectedValue(new Error('x'))
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)

    wrapper.findAllComponents(IonToggle)[1].vm.$emit('ionChange')
    await flushPromises()

    expect(wrapper.find('[role=alert]').text()).toContain('Nie udało się zapisać')
  })
})
