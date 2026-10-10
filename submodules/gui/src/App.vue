<template>
  <router-view v-slot="{ Component }">
    <!-- Only MapView is cached: it must keep its WebGL context, its POI source and the POIs it
         already fetched across /map -> /settings -> /map. Every other view is outside `include`,
         so it mounts and unmounts as before. The KeepAlive element itself is unconditional on
         purpose — wrapping it in a v-if per route would unmount it on the way out and destroy
         the very cache it exists to hold; the session-scoped `include` below is what drops it. -->
    <keep-alive :include="cached">
      <component :is="Component" />
    </keep-alive>
  </router-view>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/lib/authStore'

const router = useRouter()
const { isAuthenticated } = useAuthStore()

// The cache is scoped to the session, not to the document. A map left in the cache keeps the
// previous user's GPS fix and their POIs, and on a shared device the next user would see them
// (the M2-19 threat model). Emptying `include` makes Vue prune the entry: a deactivated MapView
// is unmounted at once, an active one loses its keep-alive flag and is destroyed by the very next
// navigation — either way `onUnmounted` runs the full teardown and the next session mounts a
// fresh view with fresh setup state.
const cached = computed(() => (isAuthenticated.value ? ['MapView'] : []))

// Guards only run on navigation; this catches a session lost mid-use (revoked family, other-device logout).
// Not immediate: the initial false must not race the boot refresh.
watch(isAuthenticated, (authenticated) => {
  if (!authenticated) {
    void router.replace('/login')
  }
})
</script>
