import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { IonButton } from '@ionic/vue'
import { clickButton, fillInputs } from './authViewsHelpers'

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router, useRoute: () => ({ query: {} }) }))
vi.mock('@/lib/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/authClient')>()),
  login: vi.fn(),
}))

import { AuthApiError, EMAIL_NOT_VERIFIED_TYPE, login } from '@/lib/authClient'
import LoginView from '@/views/LoginView.vue'

beforeEach(() => vi.clearAllMocks())

async function submit(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(LoginView)
  fillInputs(wrapper, ['user@example.com', 'secret-pw'])
  await clickButton(wrapper, 'Zaloguj')
  return wrapper
}

describe('LoginView', () => {
  it('logs in and routes to /map', async () => {
    vi.mocked(login).mockResolvedValue()
    await submit()
    expect(login).toHaveBeenCalledWith('user@example.com', 'secret-pw')
    expect(router.replace).toHaveBeenCalledWith('/map')
  })

  it('shows an error on 401 and stays on the screen', async () => {
    vi.mocked(login).mockRejectedValue(new AuthApiError(401, null, null))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('Nieprawidłowy e-mail lub hasło')
    expect(router.replace).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })

  it('routes a 403 email-not-verified (by URN) to /verify carrying the email', async () => {
    vi.mocked(login).mockRejectedValue(new AuthApiError(403, EMAIL_NOT_VERIFIED_TYPE, 'x'))
    await submit()
    expect(router.push).toHaveBeenCalledWith({ path: '/verify', query: { email: 'user@example.com' } })
  })

  it('does not route a 403 with a different type to /verify', async () => {
    vi.mocked(login).mockRejectedValue(new AuthApiError(403, 'urn:other', 'Email verification required'))
    const wrapper = await submit()
    expect(router.push).not.toHaveBeenCalled()
    expect(wrapper.find('[role=alert]').exists()).toBe(true)
  })

  it('a /login the server never answers leaves the form usable again instead of spinning for the page life', async () => {
    vi.useFakeTimers()
    try {
      const client = await vi.importActual<typeof import('@/lib/authClient')>('@/lib/authClient')
      vi.mocked(login).mockImplementationOnce(client.login)
      // Hangs whether or not it is given a signal, so an unbounded leg really stays unsettled.
      vi.stubGlobal('fetch', (_url: string, init?: RequestInit) => {
        return new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(init.signal!.reason))
        })
      })

      const wrapper = mount(LoginView)
      fillInputs(wrapper, ['user@example.com', 'secret-pw'])
      await clickButton(wrapper, 'Zaloguj')
      expect(wrapper.findAllComponents(IonButton)[0].props('disabled')).toBe(true)
      expect(wrapper.find('[role=alert]').exists()).toBe(false)

      await vi.advanceTimersByTimeAsync(10_000)

      expect(wrapper.findAllComponents(IonButton)[0].props('disabled')).toBe(false)
      expect(wrapper.find('[role=alert]').exists()).toBe(true)
      expect(wrapper.find('[role=alert]').text()).toContain('Nie udało się zalogować')
      expect(router.replace).not.toHaveBeenCalled()
    } finally {
      vi.unstubAllGlobals()
      vi.useRealTimers()
    }
  })

  it('shows a validation message on a 400 (malformed email)', async () => {
    vi.mocked(login).mockRejectedValue(new AuthApiError(400, null, null))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('poprawny adres')
  })

  // M2-19 criterion 14: a non-blocking notice when the previous logout got no 204.
  describe('unconfirmed-logout notice', () => {
    afterEach(() => vi.unstubAllGlobals())

    it('is absent on an ordinary visit, and the form still works', async () => {
      vi.mocked(login).mockResolvedValue()
      const wrapper = await submit()
      expect(wrapper.find('[role=status]').exists()).toBe(false)
      expect(router.replace).toHaveBeenCalledWith('/map')
    })

    it('is shown with a pending-logout marker and blocks nothing', async () => {
      vi.stubGlobal('localStorage', {
        getItem: (k: string) => (k === 'trippie.pendingLogout' ? '1759000000000' : null),
        setItem: vi.fn(),
        removeItem: vi.fn(),
      })
      vi.mocked(login).mockResolvedValue()

      const wrapper = await submit()

      const notice = wrapper.find('[role=status]')
      expect(notice.exists()).toBe(true)
      expect(notice.text()).toContain('nie zostało potwierdzone')
      // non-blocking: the login still went through
      expect(login).toHaveBeenCalledWith('user@example.com', 'secret-pw')
      expect(router.replace).toHaveBeenCalledWith('/map')
    })
  })
})
