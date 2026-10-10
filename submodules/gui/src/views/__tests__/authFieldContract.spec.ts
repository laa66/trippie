import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('vue-router', () => ({ useRouter: () => ({}), useRoute: () => ({ query: {} }) }))

import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import VerifyView from '@/views/VerifyView.vue'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/ResetPasswordView.vue'
import SettingsView from '@/views/SettingsView.vue'

type Field = [type: string, autocomplete: string, inputmode: string | null, maxlength: string | null]
const email: Field = ['email', 'email', null, null]
const otp: Field = ['text', 'one-time-code', 'numeric', '6']

// Native attributes, not Quasar props: these are the M2 security invariants and must survive a wrapper swap.
describe('auth field contract', () => {
  it.each<[string, Parameters<typeof mount>[0], Field[]]>([
    ['Login', LoginView, [email, ['password', 'current-password', null, null]]],
    ['Register', RegisterView, [email, ['password', 'new-password', null, null]]],
    ['Verify', VerifyView, [email, otp]],
    ['Forgot', ForgotPasswordView, [email]],
    ['Reset', ResetPasswordView, [email, otp, ['password', 'new-password', null, null]]],
  ])('%s renders its inputs with the pinned native attributes', (_name, component, expected) => {
    const inputs = mount(component).findAll('input')
    const actual = inputs.map((i): Field => [
      i.attributes('type') ?? 'text',
      i.attributes('autocomplete') ?? '',
      i.attributes('inputmode') ?? null,
      i.attributes('maxlength') ?? null,
    ])
    expect(actual).toEqual(expected)
  })

  // The screen title is pinned by copy, not by the chrome that renders it: the five auth screens
  // render it through AppScreen's `data-screen-title`.
  it.each([
    ['Login', LoginView, 'Logowanie'],
    ['Register', RegisterView, 'Rejestracja'],
    ['Verify', VerifyView, 'Weryfikacja e-mail'],
    ['Forgot', ForgotPasswordView, 'Odzyskiwanie hasła'],
    ['Reset', ResetPasswordView, 'Nowe hasło'],
  ])('%s keeps its screen title', (_name, component, title) => {
    const titled = mount(component).find('[data-screen-title]')
    expect(titled.exists()).toBe(true)
    expect(titled.text()).toBe(title)
  })

  // Settings is an app screen, not an auth screen: its title stays in the toolbar.
  it('Settings keeps its screen title', () => {
    expect(mount(SettingsView).find('.q-toolbar__title').text()).toBe('Ustawienia')
  })

  it('Settings keeps its section headers', () => {
    const headers = mount(SettingsView).findAll('.q-item__label--header').map((h) => h.text())
    expect(headers).toEqual(['Domyślny tryb treści', 'Kategorie'])
  })
})
