<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Odzyskiwanie hasła</ion-title>
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
      <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
      <p v-if="sent" role="status">Jeśli konto istnieje, wysłaliśmy na ten adres kod do zmiany hasła. Sprawdź e-mail.</p>
      <ion-button expand="block" :disabled="busy" @click="submit">Wyślij kod</ion-button>
      <ion-button expand="block" fill="clear" @click="goToReset">Mam już kod</ion-button>
      <ion-button expand="block" fill="clear" router-link="/login">Wróć do logowania</ion-button>
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent, IonItem, IonInput, IonButton } from '@ionic/vue'
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
    // Only a 400 (malformed email) is existence-independent; every other outcome must look like success.
    if (e instanceof AuthApiError && e.status === 400) {
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
