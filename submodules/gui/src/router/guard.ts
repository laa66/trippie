import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'
import { ensureSessionResolved } from '@/lib/authClient'
import { useAuthStore } from '@/lib/authStore'

/** Auth screens carry `meta.guestOnly`; every other route is protected. */
export async function authGuard(to: RouteLocationNormalized): Promise<true | RouteLocationRaw> {
  await ensureSessionResolved().catch(() => false)
  const { isAuthenticated } = useAuthStore()
  if (to.meta.guestOnly) {
    return isAuthenticated.value ? '/map' : true
  }
  return isAuthenticated.value ? true : '/login'
}
