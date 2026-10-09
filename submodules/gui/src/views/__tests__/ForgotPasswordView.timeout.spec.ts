import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { clickButton, fillInputs } from './authViewsHelpers'

const router = { push: vi.fn(), replace: vi.fn() }
vi.mock('vue-router', () => ({ useRouter: () => router, useRoute: () => ({ query: {} }) }))

// Deliberately NOT mocking @/lib/authClient: this is the one spec that drives the view through the
// REAL postJson, so the settle mode of a timed-out leg is connected to rendered output. The sibling
// spec mocks the client, which is why a postJson that resolved an aborted body read as an empty 2xx
// could flip this screen from the error copy to the success copy with nothing going red.
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ForgotPasswordView over the real authClient', () => {
  it('a 2xx whose body never streams lands on the retryable error, not the neutral confirmation', async () => {
    vi.useFakeTimers()
    try {
      vi.stubGlobal(
        'fetch',
        vi.fn((_url: string, init?: RequestInit) => {
          const signal = init!.signal!
          return Promise.resolve({
            ok: true,
            status: 200,
            json: () => new Promise((_res, rej) => signal.addEventListener('abort', () => rej(signal.reason))),
          } as unknown as Response)
        }),
      )
      const wrapper = mount(ForgotPasswordView)
      fillInputs(wrapper, ['user@example.com'])
      await clickButton(wrapper, 'Wyślij kod')
      expect(wrapper.find('[role=alert]').exists()).toBe(false)
      expect(wrapper.find('[role=status]').exists()).toBe(false)

      await vi.advanceTimersByTimeAsync(10_000)
      await flushPromises()

      expect(wrapper.find('[role=alert]').text()).toContain('Spróbuj ponownie')
      expect(wrapper.find('[role=status]').exists()).toBe(false)
      expect(wrapper.html()).not.toContain('Jeśli konto istnieje')
    } finally {
      vi.useRealTimers()
    }
  })
})
