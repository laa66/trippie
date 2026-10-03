import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const router = { replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router }))

import App from '@/App.vue'
import { clearSession, setAccessToken } from '@/lib/authStore'

const mountOpts = { global: { stubs: { IonRouterOutlet: true } } }

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
