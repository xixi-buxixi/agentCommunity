import { createApp } from 'vue'
import router from './router'
import App from './App.vue'
import { pinia } from '@/stores'
import { useThemeStore } from '@/stores/theme'
import './styles/main.css'

const app = createApp(App)
app.use(pinia)
app.use(router)

// Initialize the theme store before mounting so the first paint already has the
// persisted theme applied (the inline bootstrap in index.html sets the attribute,
// this keeps the store in sync with it).
useThemeStore(pinia)

app.mount('#app')
