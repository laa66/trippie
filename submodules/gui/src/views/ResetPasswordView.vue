<template>
  <app-screen title="Nowe hasło">
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
      <q-input
        v-model="newPassword"
        outlined
        type="password"
        autocomplete="new-password"
        label="Nowe hasło"
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
        Zmień hasło
      </q-btn>
    </div>
    <template #footer>
      <q-btn flat no-caps dense class="text-ink-muted" to="/forgot-password">Wyślij kod ponownie</q-btn>
    </template>
  </app-screen>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { QInput, QBtn } from 'quasar'
import AppScreen from '@/components/ui/AppScreen.vue'
import AppNotice from '@/components/ui/AppNotice.vue'
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
