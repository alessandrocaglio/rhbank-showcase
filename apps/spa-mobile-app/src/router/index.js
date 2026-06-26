import { createRouter, createWebHistory } from 'vue-router'
import { useKeycloak } from '../composables/useKeycloak.js'

const routes = [
  { path: '/', redirect: '/login' },
  {
    path: '/login',
    component: () => import('../views/LoginView.vue'),
  },
  {
    path: '/payments',
    component: () => import('../views/PaymentsView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/payments/:txId/status',
    component: () => import('../views/PaymentStatusView.vue'),
    meta: { requiresAuth: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth) {
    const { isAuthenticated } = useKeycloak()
    if (!isAuthenticated.value) return '/login'
  }
})

export default router
