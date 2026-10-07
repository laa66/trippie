<template>
  <ion-button :disabled="busy" @click="onLogout">Wyloguj</ion-button>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { IonButton } from '@ionic/vue'
import { logout } from '@/lib/authClient'

const router = useRouter()
const busy = ref(false)

async function onLogout() {
  busy.value = true
  await logout()
  await router.replace('/login')
}
</script>
