<template>
  <app-screen title="Rejestracja">
    <div class="space-y-4">
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
        autocomplete="new-password"
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
        Zarejestruj
      </q-btn>
    </div>
    <template #footer>
      <q-btn flat no-caps dense color="primary" to="/login">Mam już konto</q-btn>
    </template>
  </app-screen>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QInput, QBtn } from 'quasar'
import AppScreen from '@/components/ui/AppScreen.vue'
import AppNotice from '@/components/ui/AppNotice.vue'
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
