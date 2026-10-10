<template>
  <app-screen title="Logowanie">
    <div class="space-y-4">
      <app-notice v-if="logoutUnconfirmed" variant="warning" role="status">
        Poprzednie wylogowanie nie zostało potwierdzone przez serwer — sesja może być nadal aktywna.
        Dokończymy wylogowanie automatycznie.
      </app-notice>
      <q-input
        v-model="email"
        outlined
        type="email"
        autocomplete="email"
        label="E-mail"
        color="primary"
      />
      <q-input
        v-model="password"
        outlined
        type="password"
        autocomplete="current-password"
        label="Hasło"
        color="primary"
      />
      <app-notice v-if="error" variant="danger" role="alert">{{ error }}</app-notice>
      <q-btn
        class="mt-2 w-full"
        color="primary"
        unelevated
        no-caps
        size="lg"
        :disable="busy"
        :loading="busy"
        @click="submit"
      >
        Zaloguj
      </q-btn>
    </div>
    <template #footer>
      <q-btn flat no-caps dense color="primary" to="/register">Utwórz konto</q-btn>
      <q-btn flat no-caps dense class="text-ink-muted" to="/forgot-password">Nie pamiętam hasła</q-btn>
    </template>
  </app-screen>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QInput, QBtn } from 'quasar'
import AppScreen from '@/components/ui/AppScreen.vue'
import AppNotice from '@/components/ui/AppNotice.vue'
import { AuthApiError, EMAIL_NOT_VERIFIED_TYPE, login } from '@/lib/authClient'
import { hasPendingLogout } from '@/lib/pendingLogout'

const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)
// M2-19 criterion 14: a non-blocking notice, read once on mount. Nothing here gates the form — the
// sign-out is being completed in the background by the boot replay.
const logoutUnconfirmed = hasPendingLogout()

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await login(email.value, password.value)
    await router.replace('/map')
  } catch (e) {
    if (e instanceof AuthApiError && e.status === 403 && e.type === EMAIL_NOT_VERIFIED_TYPE) {
      await router.push({ path: '/verify', query: { email: email.value } })
    } else if (e instanceof AuthApiError && e.status === 401) {
      error.value = 'Nieprawidłowy e-mail lub hasło.'
    } else if (e instanceof AuthApiError && e.status === 400) {
      error.value = 'Podaj poprawny adres e-mail i hasło.'
    } else {
      error.value = 'Nie udało się zalogować. Spróbuj ponownie.'
    }
  } finally {
    busy.value = false
  }
}
</script>
