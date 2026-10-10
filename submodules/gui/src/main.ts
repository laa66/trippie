import { createApp } from 'vue'
import { Quasar } from 'quasar'
import iconSet from 'quasar/icon-set/svg-material-symbols-rounded.js'
import App from './App.vue'
import router from './router'
import { ensureSessionResolved, resumePendingLogout } from '@/lib/authClient'

/* Quasar framework CSS (sass, layered into @layer base) FIRST, then our theme: both land in
   @layer base, and within a layer the later rule wins at equal specificity, so theme.css's base
   block is what overrides Quasar's own `body` typography. theme.css also carries the Tailwind
   layers, the token chain and MapLibre's stylesheet (also into @layer base, so that Tailwind
   utilities can beat `.maplibregl-map { position: relative }`). */
import './css/quasar.scss'
import './theme.css'

// A logout the server never confirmed left a marker (M2-19): replay the bearer-less logout in the
// background so the surviving refresh cookie is revoked server-side. Started first, and it reads the
// marker synchronously, so ensureSessionResolved below still sees it and short-circuits to false
// without issuing any /refresh — refreshing first would mint a live access token on a device the
// user believes they signed out of.
void resumePendingLogout()

// Boot-time silent refresh: repopulate the in-memory access token from the httpOnly refresh cookie
// if a session exists. Started before mount; the nav guard awaits the same memoized promise.
void ensureSessionResolved()

createApp(App).use(Quasar, { iconSet }).use(router).mount('#app')
