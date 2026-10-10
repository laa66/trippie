import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const router = { replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router }))
vi.mock('@/lib/authClient', () => ({ logout: vi.fn().mockResolvedValue(undefined) }))

import { logout } from '@/lib/authClient'
import LogoutButton from '@/components/LogoutButton.vue'

beforeEach(() => vi.clearAllMocks())

describe('LogoutButton', () => {
  it('logs out and routes to /login', async () => {
    const wrapper = mount(LogoutButton)
    await wrapper.find('button').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(logout).toHaveBeenCalledOnce()
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  // M2-19 double-submit block, asserted on the rendered DOM rather than on a prop: QBtn :disable
  // renders a native <button disabled>, which really swallows the second click (P6).
  it('disables the button while the logout is in flight and swallows a second click', async () => {
    let finish!: () => void
    vi.mocked(logout).mockImplementationOnce(() => new Promise<void>((resolve) => (finish = resolve)))
    const wrapper = mount(LogoutButton)
    const button = wrapper.find('button')
    expect(button.element.disabled).toBe(false)

    await button.trigger('click')
    expect(button.element.disabled).toBe(true)

    await button.trigger('click')
    expect(logout).toHaveBeenCalledOnce()

    finish()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  it('a /logout the server never answers still drops the session and routes to /login', async () => {
    vi.useFakeTimers()
    try {
      const client = await vi.importActual<typeof import('@/lib/authClient')>('@/lib/authClient')
      const store = await import('@/lib/authStore')
      store.setAccessToken('jwt-live')
      vi.mocked(logout).mockImplementationOnce(client.logout)
      // Hangs whether or not it is given a signal, so an unbounded leg really stays unsettled.
      vi.stubGlobal('fetch', (_url: string, init?: RequestInit) => {
        return new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(init.signal!.reason))
        })
      })

      const wrapper = mount(LogoutButton)
      await wrapper.find('button').trigger('click')
      expect(router.replace).not.toHaveBeenCalled()

      await vi.advanceTimersByTimeAsync(5_000)

      expect(store.getAccessToken()).toBeNull()
      expect(store.useAuthStore().isAuthenticated.value).toBe(false)
      expect(router.replace).toHaveBeenCalledWith('/login')
    } finally {
      vi.unstubAllGlobals()
      vi.useRealTimers()
    }
  })
})
