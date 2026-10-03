import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { IonInput } from '@ionic/vue'
import { clickButton, fillInputs } from './authViewsHelpers'

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({
  useRouter: () => router,
  useRoute: () => ({ query: { email: 'user@example.com' } }),
}))
vi.mock('@/lib/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/authClient')>()),
  resetPassword: vi.fn(),
}))

import { AuthApiError, resetPassword } from '@/lib/authClient'
import ResetPasswordView from '@/views/ResetPasswordView.vue'

beforeEach(() => vi.clearAllMocks())

async function submit(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(ResetPasswordView)
  fillInputs(wrapper, ['user@example.com', '123456', 'brand-new-pw'])
  await clickButton(wrapper, 'Zmień hasło')
  return wrapper
}

describe('ResetPasswordView', () => {
  it('prefills the email from the route query', () => {
    expect(mount(ResetPasswordView).findComponent(IonInput).props('value')).toBe('user@example.com')
  })

  it('resets and routes to /login', async () => {
    vi.mocked(resetPassword).mockResolvedValue()
    await submit()
    expect(resetPassword).toHaveBeenCalledWith('user@example.com', '123456', 'brand-new-pw')
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  it('surfaces the backend 400 detail and stays on the screen', async () => {
    vi.mocked(resetPassword).mockRejectedValue(new AuthApiError(400, null, 'Invalid or expired code'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toBe('Invalid or expired code')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('renders a server detail as text, never as HTML', async () => {
    vi.mocked(resetPassword).mockRejectedValue(new AuthApiError(400, null, '<img src=x onerror=alert(1)>'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert] img').exists()).toBe(false)
    expect(wrapper.find('[role=alert]').text()).toBe('<img src=x onerror=alert(1)>')
  })

  it('shows a generic message on a non-400 failure', async () => {
    vi.mocked(resetPassword).mockRejectedValue(new AuthApiError(500, null, 'boom'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('Nie udało się zmienić hasła')
  })
})
