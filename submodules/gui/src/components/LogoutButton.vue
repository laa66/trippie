<template>
  <ion-button @click="onLogout">Wyloguj</ion-button>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { IonButton } from '@ionic/vue'
import { logout } from '@/lib/authClient'

const router = useRouter()

async function onLogout() {
  await logout()
  await router.replace('/login')
}
</script>
