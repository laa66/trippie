import { createApp } from 'vue'
import { IonicVue } from '@ionic/vue'
import App from './App.vue'
import router from './router'
import { ensureSessionResolved, resumePendingLogout } from '@/lib/authClient'

/* Ionic core CSS (its own reset — Tailwind preflight stays disabled). */
import '@ionic/vue/css/core.css'
import '@ionic/vue/css/normalize.css'
import '@ionic/vue/css/structure.css'
import '@ionic/vue/css/typography.css'
import '@ionic/vue/css/padding.css'
import '@ionic/vue/css/flex-utils.css'
import '@ionic/vue/css/display.css'

/* Tailwind entry + Ionic palette tokens. MapLibre's stylesheet is imported
   inside theme.css into @layer base so Tailwind utilities can override it (e.g.
   .maplibregl-map's default position:relative). */
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

createApp(App).use(IonicVue).use(router).mount('#app')
