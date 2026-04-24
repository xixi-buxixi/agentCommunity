import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import './styles/main.css'

const app = createApp(App)
export const pinia = createPinia()
app.use(pinia)
app.use(router)
app.mount('#app')