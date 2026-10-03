<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>Rejestracja</ion-title>
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
          type="password"
          label="Hasło"
          label-placement="floating"
          autocomplete="new-password"
          :value="password"
          @ion-input="password = String($event.detail.value ?? '')"
        />
      </ion-item>
      <p v-if="error" class="text-danger" role="alert">{{ error }}</p>
      <ion-button expand="block" :disabled="busy" @click="submit">Zarejestruj</ion-button>
      <ion-button expand="block" fill="clear" router-link="/login">Mam już konto</ion-button>
    </ion-content>
  </ion-page>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { IonPage, IonHeader, IonToolbar, IonTitle, IonContent, IonItem, IonInput, IonButton } from '@ionic/vue'
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
