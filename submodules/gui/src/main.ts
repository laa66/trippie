import { createApp } from 'vue'
import { IonicVue } from '@ionic/vue'
import { Quasar } from 'quasar'
import iconSet from 'quasar/icon-set/svg-material-symbols-rounded.js'
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

/* Quasar framework CSS (sass, layered into @layer base). */
import './css/quasar.scss'

// A logout the server never confirmed left a marker (M2-19): replay the bearer-less logout in the
// background so the surviving refresh cookie is revoked server-side. Started first, and it reads the
// marker synchronously, so ensureSessionResolved below still sees it and short-circuits to false
// without issuing any /refresh — refreshing first would mint a live access token on a device the
// user believes they signed out of.
void resumePendingLogout()

// Boot-time silent refresh: repopulate the in-memory access token from the httpOnly refresh cookie
// if a session exists. Started before mount; the nav guard awaits the same memoized promise.
void ensureSessionResolved()

// mode 'ios' is load-bearing until M2.5-05 (P7): Ionic's default platform class is literally `md`,
// which Quasar's `.md { display: none !important }` visibility helper hides, blanking <ion-app>.
// Removed together with Ionic in M2.5-05.
createApp(App).use(IonicVue, { mode: 'ios' }).use(Quasar, { iconSet }).use(router).mount('#app')
