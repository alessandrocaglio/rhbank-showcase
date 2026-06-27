import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'
import { useKeycloak } from './composables/useKeycloak.js'
import './assets/main.css'

async function bootstrap() {
  const { init } = useKeycloak()
  await init()
  createApp(App).use(router).mount('#app')
}

bootstrap()
