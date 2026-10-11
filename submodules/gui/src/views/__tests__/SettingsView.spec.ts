import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { QOptionGroup, QToggle } from 'quasar'

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
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'AUDIO', selectedCategories: ['museums'], requireProximity: true })
    vi.mocked(putSettings).mockImplementation(async (s) => s)
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)

    expect(wrapper.findComponent(QOptionGroup).props('modelValue')).toBe('AUDIO')
    expect(wrapper.findAll('.q-radio')).toHaveLength(3)
    expect(wrapper.findAll('.q-radio').map((r) => r.text())).toEqual(['Tekst', 'Audio', 'Tekst i audio'])
    const [, ...toggles] = wrapper.findAllComponents(QToggle)
    expect(toggles).toHaveLength(8)
    expect(toggles.filter((t) => t.props('modelValue')).map((t) => t.text())).toEqual(['Muzea'])

    toggles[1].vm.$emit('update:model-value', true)
    await flushPromises()
    expect(putSettings).toHaveBeenCalledWith({ defaultContentMode: 'AUDIO', selectedCategories: ['museums', 'monuments'], requireProximity: true }, expect.anything())
  })

  it('a changed content mode is PUT; an unchanged one is not', async () => {
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['museums'], requireProximity: true })
    vi.mocked(putSettings).mockImplementation(async (s) => s)
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)
    const group = wrapper.findComponent(QOptionGroup)

    group.vm.$emit('update:model-value', 'BOTH')
    await flushPromises()
    expect(putSettings).not.toHaveBeenCalled()

    group.vm.$emit('update:model-value', 'TEXT')
    await flushPromises()
    expect(putSettings).toHaveBeenCalledWith({ defaultContentMode: 'TEXT', selectedCategories: ['museums'], requireProximity: true }, expect.anything())
  })

  it('shows requireProximity and writes its change through PUT', async () => {
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['museums'], requireProximity: false })
    vi.mocked(putSettings).mockImplementation(async (s) => s)
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)
    const toggle = wrapper.findAllComponents(QToggle)[0]

    expect(toggle.text()).toBe('Wymagaj zbliżenia do miejsca')
    expect(toggle.props('modelValue')).toBe(false)

    toggle.vm.$emit('update:model-value', true)
    await flushPromises()

    expect(putSettings).toHaveBeenCalledWith({ defaultContentMode: 'BOTH', selectedCategories: ['museums'], requireProximity: true }, expect.anything())
    expect(wrapper.findAllComponents(QToggle)[0].props('modelValue')).toBe(true)
  })

  it('renders the revert error as text after a failed save', async () => {
    vi.mocked(getSettings).mockResolvedValue({ defaultContentMode: 'BOTH', selectedCategories: ['museums'], requireProximity: true })
    vi.mocked(putSettings).mockRejectedValue(new Error('x'))
    setAccessToken('jwt')
    await flushPromises()
    const wrapper = mount(SettingsView)

    wrapper.findAllComponents(QToggle)[2].vm.$emit('update:model-value', true)
    await flushPromises()

    expect(wrapper.find('[role=alert]').text()).toContain('Nie udało się zapisać')
  })
})
