import { createRouter, createWebHistory } from '@ionic/vue-router'
import type { RouteRecordRaw } from 'vue-router'
import HomePage from '@/views/HomePage.vue'
import MapView from '@/views/MapView.vue'

const routes: RouteRecordRaw[] = [
    { path: '/', redirect: '/home' },
    { path: '/home', component: HomePage },
    { path: '/map', component: MapView },

]

export default createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes,
})