<template>
  <!-- `min-h-11` + `px-2` instead of `dense`: dense shrinks the whole button, and Quasar's own
       default lands a flat text button at 36px - both under the frozen 44px touch floor. This
       button signs the user out, so it is the last one that should be hard to hit. -->
  <q-btn flat no-caps class="min-h-11 px-2" label="Wyloguj" :disable="busy" @click="onLogout" />
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
