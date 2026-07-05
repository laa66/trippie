import { createApp } from 'vue'
import { IonicVue } from '@ionic/vue'
import App from './App.vue'
import router from './router'

/* Ionic core CSS (its own reset — Tailwind preflight stays disabled). */
import '@ionic/vue/css/core.css'
import '@ionic/vue/css/normalize.css'
import '@ionic/vue/css/structure.css'
import '@ionic/vue/css/typography.css'
import '@ionic/vue/css/padding.css'
import '@ionic/vue/css/flex-utils.css'
import '@ionic/vue/css/display.css'

/* MapLibre GL renderer styles (controls, attribution, canvas). */
import 'maplibre-gl/dist/maplibre-gl.css'

/* Tailwind entry + Ionic palette tokens. */
import './theme.css'

createApp(App).use(IonicVue).use(router).mount('#app')
