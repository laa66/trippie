<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Nowe hasło</ion-title>
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
      <ion-item>
        <ion-input
          type="password"
          label="Nowe hasło"
          label-placement="floating"
          autocomplete="new-password"
          :value="newPassword"
          @ion-input="newPassword = String($event.detail.value ?? '')"
        />
      </ion-item>
      <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
      <ion-button expand="block" :disabled="busy" @click="submit">Zmień hasło</ion-button>
      <ion-button expand="block" fill="clear" router-link="/forgot-password">Wyślij kod ponownie</ion-button>
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent, IonItem, IonInput, IonButton } from '@ionic/vue'
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
