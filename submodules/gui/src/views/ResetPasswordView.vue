<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Nowe hasło</q-toolbar-title>
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
          v-model="code"
          outlined
          inputmode="numeric"
          :maxlength="6"
          autocomplete="one-time-code"
          label="Kod z e-maila"
        />
        <q-input
          v-model="newPassword"
          outlined
          type="password"
          autocomplete="new-password"
          label="Nowe hasło"
        />
        <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
        <q-btn class="w-full" :disable="busy" @click="submit">Zmień hasło</q-btn>
        <q-btn class="w-full" flat to="/forgot-password">Wyślij kod ponownie</q-btn>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { QLayout, QHeader, QToolbar, QToolbarTitle, QPageContainer, QPage, QInput, QBtn } from 'quasar'
import { AuthApiError, resetPassword } from '@/lib/authClient'

const route = useRoute()
const router = useRouter()
const email = ref(typeof route.query.email === 'string' ? route.query.email : '')
const code = ref('')
const newPassword = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await resetPassword(email.value, code.value, newPassword.value)
    await router.replace('/login')
  } catch (e) {
    if (e instanceof AuthApiError && e.status === 400) {
      error.value = e.detail ?? 'Nieprawidłowy lub wygasły kod albo zbyt słabe hasło.'
    } else {
      error.value = 'Nie udało się zmienić hasła. Spróbuj ponownie.'
    }
  } finally {
    busy.value = false
  }
}
</script>
