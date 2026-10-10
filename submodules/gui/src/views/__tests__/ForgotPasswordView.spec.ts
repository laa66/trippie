import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { clickButton, fillInputs } from './authViewsHelpers'

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router, useRoute: () => ({ query: {} }) }))
vi.mock('@/lib/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/authClient')>()),
  requestPasswordReset: vi.fn(),
}))

import { AuthApiError, requestPasswordReset } from '@/lib/authClient'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'

beforeEach(() => vi.clearAllMocks())

async function submit(email = 'user@example.com'): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(ForgotPasswordView)
  await fillInputs(wrapper, [email])
  await clickButton(wrapper, 'Wyślij kod')
  return wrapper
}

describe('ForgotPasswordView', () => {
  it('posts the email and shows the neutral confirmation', async () => {
    vi.mocked(requestPasswordReset).mockResolvedValue()
    const wrapper = await submit()
    expect(requestPasswordReset).toHaveBeenCalledWith('user@example.com')
    expect(wrapper.find('[role=status]').text()).toContain('Jeśli konto istnieje')
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
  })

  // No-enumeration guarantee: every status except 400 must render the same neutral output, so the
  // screen can never reveal whether an account exists. Listed statuses, not a closed trio.
  it.each([200, 403, 404, 409, 429, 500])('renders the identical neutral output for status %i', async (status) => {
    const mock = vi.mocked(requestPasswordReset)
    if (status === 200) mock.mockResolvedValue()
    else mock.mockRejectedValue(new AuthApiError(status, null, 'x'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').exists()).toBe(false)
    expect(wrapper.find('[role=status]').text()).toContain('Jeśli konto istnieje')
  })

  it('shows a validation message on 400 (malformed email)', async () => {
    vi.mocked(requestPasswordReset).mockRejectedValue(new AuthApiError(400, null, null))
    const wrapper = await submit('nope')
    expect(wrapper.find('[role=alert]').text()).toContain('poprawny adres')
    expect(wrapper.find('[role=status]').exists()).toBe(false)
  })

  it('a local failure that never reached the server shows a retryable error, not the success copy', async () => {
    vi.mocked(requestPasswordReset).mockRejectedValue(new DOMException('timeout', 'TimeoutError'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('Spróbuj ponownie')
    expect(wrapper.find('[role=status]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('Jeśli konto istnieje')
  })

  it('an offline failure shows the retryable error, not the success copy', async () => {
    vi.mocked(requestPasswordReset).mockRejectedValue(new TypeError('offline'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('Spróbuj ponownie')
    expect(wrapper.find('[role=status]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('Jeśli konto istnieje')
  })

  it('"Mam już kod" routes to /reset-password carrying only the email', async () => {
    const wrapper = mount(ForgotPasswordView)
    await fillInputs(wrapper, ['user@example.com'])
    await clickButton(wrapper, 'Mam już kod')
    expect(router.push).toHaveBeenCalledWith({ path: '/reset-password', query: { email: 'user@example.com' } })
  })
})
