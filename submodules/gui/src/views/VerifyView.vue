<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Weryfikacja e-mail</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content class="ion-padding">
      <ion-item>
        <ion-input
          type="email"
          label="E-mail"
          label-placement="floating"
          autocomplete="email"
          :value="email"
          @ion-input="email = String($event.detail.value ?? '')"
        />
      </ion-item>
      <ion-item>
        <ion-input
          label="Kod z e-maila"
          label-placement="floating"
          inputmode="numeric"
          :maxlength="6"
          autocomplete="one-time-code"
          :value="code"
          @ion-input="code = String($event.detail.value ?? '')"
        />
      </ion-item>
      <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
      <p v-if="info" role="status">{{ info }}</p>
      <ion-button expand="block" :disabled="busy" @click="submit">Potwierdź</ion-button>
      <ion-button expand="block" fill="clear" :disabled="busy" @click="resend">Wyślij kod ponownie</ion-button>
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent, IonItem, IonInput, IonButton } from '@ionic/vue'
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
