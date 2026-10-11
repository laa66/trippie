<template>
  <app-screen title="Weryfikacja e-mail">
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
        v-model="code"
        outlined
        inputmode="numeric"
        :maxlength="6"
        autocomplete="one-time-code"
        label="Kod z e-maila"
        color="primary"
      />
      <app-notice v-if="error" variant="danger" role="alert">{{ error }}</app-notice>
      <app-notice v-if="info" variant="info" role="status">{{ info }}</app-notice>
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
        Potwierdź
      </q-btn>
    </div>
    <template #footer>
      <q-btn flat no-caps class="min-h-11 px-3 text-ink-muted" :disable="busy" @click="resend">Wyślij kod ponownie</q-btn>
    </template>
  </app-screen>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { QInput, QBtn } from 'quasar'
import AppScreen from '@/components/ui/AppScreen.vue'
import AppNotice from '@/components/ui/AppNotice.vue'
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
