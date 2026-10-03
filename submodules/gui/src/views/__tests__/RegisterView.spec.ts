import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { clickButton, fillInputs } from './authViewsHelpers'

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router, useRoute: () => ({ query: {} }) }))
vi.mock('@/lib/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/authClient')>()),
  register: vi.fn(),
}))

import { AuthApiError, register } from '@/lib/authClient'
import RegisterView from '@/views/RegisterView.vue'

beforeEach(() => vi.clearAllMocks())

async function submit(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(RegisterView)
  fillInputs(wrapper, ['new@example.com', 'long-enough-pw'])
  await clickButton(wrapper, 'Zarejestruj')
  return wrapper
}

describe('RegisterView', () => {
  it('registers and routes to /verify with the email', async () => {
    vi.mocked(register).mockResolvedValue()
    await submit()
    expect(register).toHaveBeenCalledWith('new@example.com', 'long-enough-pw')
    expect(router.push).toHaveBeenCalledWith({ path: '/verify', query: { email: 'new@example.com' } })
  })

  it('shows a duplicate-email message on 409', async () => {
    vi.mocked(register).mockRejectedValue(new AuthApiError(409, null, null))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toContain('już istnieje')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('shows the server detail on 400 (weak password)', async () => {
    vi.mocked(register).mockRejectedValue(new AuthApiError(400, null, 'password too short'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert]').text()).toBe('password too short')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('renders a server detail as text, never as HTML', async () => {
    vi.mocked(register).mockRejectedValue(new AuthApiError(400, null, '<img src=x onerror=alert(1)>'))
    const wrapper = await submit()
    expect(wrapper.find('[role=alert] img').exists()).toBe(false)
  })
})
