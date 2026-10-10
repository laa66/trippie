import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { KeepAlive, defineComponent, h } from 'vue'

const router = { replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router }))
// The route table reaches MapView, which value-imports maplibre-gl and the settings stack.
vi.mock('maplibre-gl', () => ({ Map: class {}, Marker: class {} }))
vi.mock('@/lib/settingsApi', () => ({ getSettings: vi.fn(() => new Promise(() => {})), putSettings: vi.fn() }))

import App from '@/App.vue'
import MapView from '@/views/MapView.vue'
import { clearSession, setAccessToken } from '@/lib/authStore'

const Routed = defineComponent({ name: 'Routed', render: () => h('div') })

// RouterView hands the matched component to its slot. The old `RouterView: true` stub swallowed
// that slot, which hid App.vue's <keep-alive> from every assertion here — a typo in `include`
// stayed green while the cache was dead.
const RouterViewStub = defineComponent({
  setup(_props, { slots }) {
    return () => slots.default?.({ Component: Routed, route: {} })
  },
})

const mountOpts = { global: { stubs: { RouterView: RouterViewStub } } }

beforeEach(() => {
  vi.clearAllMocks()
  clearSession()
})

describe('App session watcher', () => {
  it('routes to /login when the session is lost mid-use', async () => {
    setAccessToken('jwt')
    mount(App, mountOpts)
    clearSession()
    await flushPromises()
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  it('does not navigate on mount while logged out (false -> false)', async () => {
    mount(App, mountOpts)
    await flushPromises()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('does not bounce a cold boot that resolves to logged-in', async () => {
    mount(App, mountOpts)
    setAccessToken('jwt-boot')
    await flushPromises()
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('App keep-alive cache', () => {
  // Asserted against MapView's own name, not a string literal: a typo on either side fails here
  // instead of silently disabling the cache.
  it('caches the map view while a session is live', async () => {
    setAccessToken('jwt')
    const wrapper = mount(App, mountOpts)
    await flushPromises()

    expect(MapView.name).toBe('MapView')
    expect(wrapper.findComponent(KeepAlive).props('include')).toContain(MapView.name)
  })

  it('drops the cache when the session ends, so the next user gets a fresh map', async () => {
    setAccessToken('jwt')
    const wrapper = mount(App, mountOpts)
    await flushPromises()

    clearSession()
    await flushPromises()

    expect(wrapper.findComponent(KeepAlive).props('include')).toEqual([])
  })
})
