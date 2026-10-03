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
    await wrapper.find('ion-button').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(logout).toHaveBeenCalledOnce()
    expect(router.replace).toHaveBeenCalledWith('/login')
  })
})
