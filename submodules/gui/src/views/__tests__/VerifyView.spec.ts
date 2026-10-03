import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { IonInput } from '@ionic/vue'
import { clickButton, fillInputs } from './authViewsHelpers'

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({
  useRouter: () => router,
  useRoute: () => ({ query: { email: 'new@example.com' } }),
}))
vi.mock('@/lib/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/authClient')>()),
  verifyEmail: vi.fn(),
  resendCode: vi.fn(),
}))

import { AuthApiError, resendCode, verifyEmail } from '@/lib/authClient'
import VerifyView from '@/views/VerifyView.vue'

beforeEach(() => vi.clearAllMocks())

describe('VerifyView', () => {
  it('verifies with the email from the route query and routes to /login', async () => {
    vi.mocked(verifyEmail).mockResolvedValue()
    const wrapper = mount(VerifyView)
    fillInputs(wrapper, ['new@example.com', '123456'])
    await clickButton(wrapper, 'Potwierdź')
    expect(verifyEmail).toHaveBeenCalledWith('new@example.com', '123456')
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  it('prefills the email input from the route query', () => {
    const wrapper = mount(VerifyView)
    expect(wrapper.findComponent(IonInput).props('value')).toBe('new@example.com')
  })

  it('shows the backend message on a 400 wrong/expired code', async () => {
    vi.mocked(verifyEmail).mockRejectedValue(new AuthApiError(400, null, 'Invalid or expired code'))
    const wrapper = mount(VerifyView)
    fillInputs(wrapper, ['new@example.com', '000000'])
    await clickButton(wrapper, 'Potwierdź')
    expect(wrapper.find('[role=alert]').text()).toBe('Invalid or expired code')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('resends the code and confirms', async () => {
    vi.mocked(resendCode).mockResolvedValue()
    const wrapper = mount(VerifyView)
    await clickButton(wrapper, 'Wyślij kod ponownie')
    expect(resendCode).toHaveBeenCalledWith('new@example.com')
    expect(wrapper.find('[role=status]').text()).toContain('nowy kod')
  })

  it('shows a throttle message on a 429 resend', async () => {
    vi.mocked(resendCode).mockRejectedValue(new AuthApiError(429, null, null))
    const wrapper = mount(VerifyView)
    await clickButton(wrapper, 'Wyślij kod ponownie')
    expect(wrapper.find('[role=alert]').text()).toContain('Zbyt wiele')
    expect(wrapper.find('[role=status]').exists()).toBe(false)
  })
})
