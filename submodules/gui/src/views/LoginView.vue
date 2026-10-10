<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Logowanie</q-toolbar-title>
      </q-toolbar>
    </q-header>
    <q-page-container>
      <q-page class="p-4">
        <p v-if="logoutUnconfirmed" class="text-warning" role="status">
          Poprzednie wylogowanie nie zostało potwierdzone przez serwer — sesja może być nadal aktywna.
          Dokończymy wylogowanie automatycznie.
        </p>
        <q-input
          v-model="email"
          outlined
          type="email"
          autocomplete="email"
          label="E-mail"
        />
        <q-input
          v-model="password"
          outlined
          type="password"
          autocomplete="current-password"
          label="Hasło"
        />
        <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
        <q-btn class="w-full" :disable="busy" @click="submit">Zaloguj</q-btn>
        <q-btn class="w-full" flat to="/forgot-password">Nie pamiętam hasła</q-btn>
        <q-btn class="w-full" flat to="/register">Utwórz konto</q-btn>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QLayout, QHeader, QToolbar, QToolbarTitle, QPageContainer, QPage, QInput, QBtn } from 'quasar'
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
