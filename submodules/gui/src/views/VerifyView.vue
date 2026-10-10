<template>
  <q-layout view="hHh lpR fFf">
    <q-header>
      <q-toolbar>
        <q-toolbar-title>Weryfikacja e-mail</q-toolbar-title>
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
        <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
        <p v-if="info" role="status">{{ info }}</p>
        <q-btn class="w-full" :disable="busy" @click="submit">Potwierdź</q-btn>
        <q-btn class="w-full" flat :disable="busy" @click="resend">Wyślij kod ponownie</q-btn>
      </q-page>
    </q-page-container>
  </q-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { QLayout, QHeader, QToolbar, QToolbarTitle, QPageContainer, QPage, QInput, QBtn } from 'quasar'
import { AuthApiError, resendCode, verifyEmail } from '@/lib/authClient'

const route = useRoute()
const router = useRouter()
const email = ref(typeof route.query.email === 'string' ? route.query.email : '')
const code = ref('')
const error = ref('')
const info = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  info.value = ''
  busy.value = true
  try {
    await verifyEmail(email.value, code.value)
    await router.replace('/login')
  } catch (e) {
    if (e instanceof AuthApiError && e.status === 400) {
      error.value = e.detail ?? 'Nieprawidłowy lub wygasły kod.'
    } else {
      error.value = 'Nie udało się zweryfikować konta. Spróbuj ponownie.'
    }
  } finally {
    busy.value = false
  }
}

async function resend() {
  error.value = ''
  info.value = ''
  busy.value = true
  try {
    await resendCode(email.value)
    info.value = 'Wysłaliśmy nowy kod.'
  } catch (e) {
    if (e instanceof AuthApiError && e.status === 429) {
      error.value = 'Zbyt wiele próśb. Spróbuj ponownie za chwilę.'
    } else {
      error.value = 'Nie udało się wysłać kodu. Spróbuj ponownie.'
    }
  } finally {
    busy.value = false
  }
}
</script>
