<template>
  <ion-app>
    <ion-router-outlet />
  </ion-app>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import { useRouter } from 'vue-router'
import { IonApp, IonRouterOutlet } from '@ionic/vue'
import { useAuthStore } from '@/lib/authStore'

const router = useRouter()
const { isAuthenticated } = useAuthStore()

// Guards only run on navigation; this catches a session lost mid-use (revoked family, other-device logout).
// Not immediate: the initial false must not race the boot refresh.
watch(isAuthenticated, (authenticated) => {
  if (!authenticated) {
    void router.replace('/login')
  }
})
</script>
