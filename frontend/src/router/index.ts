import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
    { path: '/chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { requiresAuth: true } },
    { path: '/chat/:id', name: 'chat-detail', component: () => import('../views/ChatView.vue'), meta: { requiresAuth: true } },
    { path: '/reports', name: 'reports', component: () => import('../views/ReportsView.vue') },
    { path: '/reports/:id', name: 'report-detail', component: () => import('../views/ReportDetailView.vue') },
    { path: '/knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
    { path: '/knowledge/:id', name: 'knowledge-detail', component: () => import('../views/KnowledgeDetailView.vue') },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue') },
    { path: '/admin', name: 'admin', component: () => import('../views/AdminView.vue'), meta: { requiresAuth: true, requiresAdmin: true } }
  ]
})

router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    next({ name: 'login', query: { redirect: to.fullPath } })
  } else if (to.meta.requiresAdmin && userStore.role !== 'ADMIN') {
    next({ name: 'home' })
  } else {
    next()
  }
})

export default router
