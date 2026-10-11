<template>
  <app-screen title="Odzyskiwanie hasła">
    <div class="space-y-4">
      <q-input
        v-model="email"
        outlined
        type="email"
        autocomplete="email"
        label="E-mail"
        color="primary"
      />
      <app-notice v-if="error" variant="danger" role="alert">{{ error }}</app-notice>
      <app-notice v-if="sent" variant="info" role="status">
        Jeśli konto istnieje, wysłaliśmy na ten adres kod do zmiany hasła. Sprawdź e-mail.
      </app-notice>
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
        Wyślij kod
      </q-btn>
    </div>
    <template #footer>
      <q-btn flat no-caps color="primary" class="min-h-11 px-3" @click="goToReset">Mam już kod</q-btn>
      <q-btn flat no-caps class="min-h-11 px-3 text-ink-muted" to="/login">Wróć do logowania</q-btn>
    </template>
  </app-screen>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QInput, QBtn } from 'quasar'
import AppScreen from '@/components/ui/AppScreen.vue'
import AppNotice from '@/components/ui/AppNotice.vue'
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
