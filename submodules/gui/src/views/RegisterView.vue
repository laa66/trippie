<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Rejestracja</q-toolbar-title>
      </q-toolbar>
    </q-header>
    <q-page-container>
      <q-page class="p-4">
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
          autocomplete="new-password"
          label="Hasło"
        />
        <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
        <q-btn class="w-full" :disable="busy" @click="submit">Zarejestruj</q-btn>
        <q-btn class="w-full" flat to="/login">Mam już konto</q-btn>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QLayout, QHeader, QToolbar, QToolbarTitle, QPageContainer, QPage, QInput, QBtn } from 'quasar'
import { AuthApiError, register } from '@/lib/authClient'

const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await register(email.value, password.value)
    await router.push({ path: '/verify', query: { email: email.value } })
  } catch (e) {
    if (e instanceof AuthApiError && e.status === 409) {
      error.value = 'Konto z tym adresem e-mail już istnieje.'
    } else if (e instanceof AuthApiError && e.status === 400) {
      error.value = e.detail ?? 'Nieprawidłowy e-mail lub hasło (min. 8 znaków).'
    } else {
      error.value = 'Nie udało się założyć konta. Spróbuj ponownie.'
    }
  } finally {
    busy.value = false
  }
}
</script>
