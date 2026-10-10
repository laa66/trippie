<template>
  <q-btn flat label="Wyloguj" :disable="busy" @click="onLogout" />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { QBtn } from 'quasar'
import { logout } from '@/lib/authClient'

const router = useRouter()
const busy = ref(false)

async function onLogout() {
  busy.value = true
  await logout()
  await router.replace('/login')
}
</script>
