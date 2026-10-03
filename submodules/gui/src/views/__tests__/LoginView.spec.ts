import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
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

  it('shows a validation message on a 400 (malformed email)', async () => {
    vi.mocked(login).mockRejectedValue(new AuthApiError(400, null, null))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('poprawny adres')
  })
})
