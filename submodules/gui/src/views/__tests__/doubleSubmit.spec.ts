import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { clickButton, fillInputs } from './authViewsHelpers'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }), useRoute: () => ({ query: {} }) }))
vi.mock('@/lib/authClient', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/authClient')>()),
  register: vi.fn(),
  verifyEmail: vi.fn(),
  resendCode: vi.fn(),
  requestPasswordReset: vi.fn(),
  resetPassword: vi.fn(),
}))

import { register, verifyEmail, resendCode, requestPasswordReset, resetPassword } from '@/lib/authClient'
import RegisterView from '@/views/RegisterView.vue'
import VerifyView from '@/views/VerifyView.vue'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/ResetPasswordView.vue'

beforeEach(() => {
  vi.clearAllMocks()
  for (const fn of [register, verifyEmail, resendCode, requestPasswordReset, resetPassword]) {
    vi.mocked(fn).mockImplementation(() => new Promise<void>(() => {}))
  }
})

const button = (w: VueWrapper, text: string) => w.findAll('button').find((b) => b.text().includes(text))!

// tabindex/aria-disabled prove Quasar's own `disable` ran, not a stray `disabled` attribute falling through.
function expectBlocked(w: VueWrapper, text: string) {
  const b = button(w, text)
  expect(b.element.disabled).toBe(true)
  expect(b.attributes('tabindex')).toBe('-1')
  expect(b.attributes('aria-disabled')).toBe('true')
}

describe('double-submit is blocked while a request is in flight', () => {
  it('RegisterView', async () => {
    const w = mount(RegisterView)
    await fillInputs(w, ['a@example.com', 'long-enough-pw'])
    await clickButton(w, 'Zarejestruj')
    expectBlocked(w, 'Zarejestruj')
    await button(w, 'Zarejestruj').trigger('click')
    expect(register).toHaveBeenCalledTimes(1)
  })

  it('ForgotPasswordView', async () => {
    const w = mount(ForgotPasswordView)
    await fillInputs(w, ['a@example.com'])
    await clickButton(w, 'Wyślij kod')
    expectBlocked(w, 'Wyślij kod')
    await button(w, 'Wyślij kod').trigger('click')
    expect(requestPasswordReset).toHaveBeenCalledTimes(1)
  })

  it('ResetPasswordView', async () => {
    const w = mount(ResetPasswordView)
    await fillInputs(w, ['a@example.com', '123456', 'brand-new-pw'])
    await clickButton(w, 'Zmień hasło')
    expectBlocked(w, 'Zmień hasło')
    await button(w, 'Zmień hasło').trigger('click')
    expect(resetPassword).toHaveBeenCalledTimes(1)
  })

  it('VerifyView: submitting in flight blocks both submit and resend', async () => {
    const w = mount(VerifyView)
    await fillInputs(w, ['a@example.com', '123456'])
    await clickButton(w, 'Potwierdź')
    expectBlocked(w, 'Potwierdź')
    expectBlocked(w, 'Wyślij kod ponownie')
    await button(w, 'Potwierdź').trigger('click')
    await button(w, 'Wyślij kod ponownie').trigger('click')
    expect(verifyEmail).toHaveBeenCalledTimes(1)
    expect(resendCode).not.toHaveBeenCalled()
  })

  it('VerifyView: resending in flight blocks both resend and submit', async () => {
    const w = mount(VerifyView)
    await fillInputs(w, ['a@example.com', '123456'])
    await clickButton(w, 'Wyślij kod ponownie')
    expectBlocked(w, 'Wyślij kod ponownie')
    expectBlocked(w, 'Potwierdź')
    await button(w, 'Wyślij kod ponownie').trigger('click')
    await button(w, 'Potwierdź').trigger('click')
    expect(resendCode).toHaveBeenCalledTimes(1)
    expect(verifyEmail).not.toHaveBeenCalled()
  })
})
