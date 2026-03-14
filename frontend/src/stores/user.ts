import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../services/api'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userId = ref<number | null>(null)
  const email = ref('')
  const nickname = ref('')
  const role = ref('')

  const isLoggedIn = computed(() => !!token.value)

  async function login(loginEmail: string, password: string) {
    const res: any = await api.post('/auth/login', { email: loginEmail, password })
    setAuth(res.data)
  }

  async function register(registerEmail: string, code: string, password: string, nick: string) {
    const res: any = await api.post('/auth/register', { email: registerEmail, code, password, nickname: nick })
    setAuth(res.data)
  }

  async function sendCode(codeEmail: string) {
    await api.post('/auth/send-code', { email: codeEmail })
  }

  async function resetPassword(resetEmail: string, code: string, newPassword: string) {
    await api.post('/auth/reset-password', { email: resetEmail, code, newPassword })
  }

  function setAuth(data: any) {
    token.value = data.token
    userId.value = data.userId
    email.value = data.email
    nickname.value = data.nickname
    role.value = data.role
    localStorage.setItem('token', data.token)
  }

  function logout() {
    token.value = ''
    userId.value = null
    email.value = ''
    nickname.value = ''
    role.value = ''
    localStorage.removeItem('token')
  }

  async function fetchMe() {
    if (!token.value) return
    try {
      const res: any = await api.get('/auth/me')
      userId.value = res.data.id
      email.value = res.data.email
      nickname.value = res.data.nickname
      role.value = res.data.role
    } catch {
      logout()
    }
  }

  return { token, userId, email, nickname, role, isLoggedIn, login, register, sendCode, resetPassword, logout, fetchMe }
})
