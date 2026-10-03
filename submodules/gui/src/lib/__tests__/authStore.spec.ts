import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Module-singleton store: reset the module graph per test so token state never leaks between cases.
beforeEach(() => {
  vi.resetModules()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('authStore', () => {
  it('holds the access token in memory only — never writes to web storage', async () => {
    const setItem = vi.fn()
    vi.stubGlobal('localStorage', { getItem: vi.fn(), setItem, removeItem: vi.fn() })
    vi.stubGlobal('sessionStorage', { getItem: vi.fn(), setItem, removeItem: vi.fn() })

    const store = await import('@/lib/authStore')
    store.setAccessToken('jwt-in-memory')

    expect(store.getAccessToken()).toBe('jwt-in-memory')
    expect(setItem).not.toHaveBeenCalled()
  })

  it('starts logged-out and flips the reactive signal on set / clear', async () => {
    const store = await import('@/lib/authStore')
    const { isAuthenticated } = store.useAuthStore()

    expect(isAuthenticated.value).toBe(false)
    expect(store.getAccessToken()).toBeNull()

    store.setAccessToken('jwt')
    expect(isAuthenticated.value).toBe(true)

    store.clearSession()
    expect(isAuthenticated.value).toBe(false)
    expect(store.getAccessToken()).toBeNull()
  })

  it('markLoggedOut sets the latch and setAccessToken resets it', async () => {
    const store = await import('@/lib/authStore')
    expect(store.isLoggedOutLatched()).toBe(false)
    store.markLoggedOut()
    expect(store.isLoggedOutLatched()).toBe(true)
    store.setAccessToken('jwt')
    expect(store.isLoggedOutLatched()).toBe(false)
  })
})
