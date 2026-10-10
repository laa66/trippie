<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Odzyskiwanie hasła</q-toolbar-title>
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
        <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
        <p v-if="sent" role="status">Jeśli konto istnieje, wysłaliśmy na ten adres kod do zmiany hasła. Sprawdź e-mail.</p>
        <q-btn class="w-full" :disable="busy" @click="submit">Wyślij kod</q-btn>
        <q-btn class="w-full" flat @click="goToReset">Mam już kod</q-btn>
        <q-btn class="w-full" flat to="/login">Wróć do logowania</q-btn>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QLayout, QHeader, QToolbar, QToolbarTitle, QPageContainer, QPage, QInput, QBtn } from 'quasar'
import { AuthApiError, requestPasswordReset } from '@/lib/authClient'

const router = useRouter()
const email = ref('')
const error = ref('')
const sent = ref(false)
const busy = ref(false)

async function submit() {
  error.value = ''
  sent.value = false
  busy.value = true
  try {
    await requestPasswordReset(email.value)
  } catch (e) {
    // Existence-independent outcomes surface: a 400 (malformed email) and a local failure (timeout, offline)
    // that never reached the server. Every other server answer must look like success.
    if (!(e instanceof AuthApiError)) {
      error.value = 'Nie udało się wysłać kodu. Spróbuj ponownie.'
    } else if (e.status === 400) {
      error.value = 'Podaj poprawny adres e-mail.'
    }
  } finally {
    sent.value = error.value === ''
    busy.value = false
  }
}

function goToReset() {
  return router.push({ path: '/reset-password', query: { email: email.value } })
}
</script>
