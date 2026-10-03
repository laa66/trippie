import { createRouter, createWebHistory } from '@ionic/vue-router'
import type { RouteRecordRaw } from 'vue-router'
import MapView from '@/views/MapView.vue'
import LoginView from '@/views/LoginView.vue'
import RegisterView from '@/views/RegisterView.vue'
import VerifyView from '@/views/VerifyView.vue'
import ForgotPasswordView from '@/views/ForgotPasswordView.vue'
import ResetPasswordView from '@/views/ResetPasswordView.vue'
import SettingsView from '@/views/SettingsView.vue'
import { authGuard } from './guard'

export const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/map' },
  { path: '/map', component: MapView },
  { path: '/settings', component: SettingsView },
  { path: '/login', component: LoginView, meta: { guestOnly: true } },
  { path: '/register', component: RegisterView, meta: { guestOnly: true } },
  { path: '/verify', component: VerifyView, meta: { guestOnly: true } },
  { path: '/forgot-password', component: ForgotPasswordView, meta: { guestOnly: true } },
  { path: '/reset-password', component: ResetPasswordView, meta: { guestOnly: true } },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach(authGuard)

export default router
