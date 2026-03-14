<template>
  <div class="min-h-[calc(100vh-3.5rem)] flex items-center justify-center bg-gray-50 px-4">
    <div class="bg-white p-8 rounded-2xl shadow-sm border border-gray-200 w-full max-w-md">
      <!-- 登录模式 -->
      <template v-if="mode === 'login'">
        <h2 class="text-2xl font-bold text-center mb-6">登录</h2>
        <form @submit.prevent="handleLogin" class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">邮箱</label>
            <input v-model="email" type="email" required
              class="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">密码</label>
            <input v-model="password" type="password" required
              class="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
          </div>
          <p v-if="error" class="text-red-500 text-sm">{{ error }}</p>
          <button type="submit" :disabled="loading"
            class="w-full bg-primary text-white py-2.5 rounded-lg hover:bg-primary-dark disabled:opacity-50 transition font-medium">
            {{ loading ? '登录中...' : '登录' }}
          </button>
        </form>
        <div class="flex justify-between text-sm text-gray-500 mt-4">
          <button @click="switchMode('reset')" class="text-primary hover:underline">忘记密码？</button>
          <router-link to="/register" class="text-primary hover:underline">注册账号</router-link>
        </div>
      </template>

      <!-- 重置密码模式 -->
      <template v-else>
        <h2 class="text-2xl font-bold text-center mb-6">重置密码</h2>
        <form @submit.prevent="handleReset" class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">邮箱</label>
            <input v-model="email" type="email" required
              class="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">验证码</label>
            <div class="flex space-x-2">
              <input v-model="code" type="text" required maxlength="6"
                class="flex-1 border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
              <button type="button" @click="handleSendCode" :disabled="codeCooldown > 0"
                class="bg-gray-100 text-gray-700 px-4 py-2.5 rounded-lg hover:bg-gray-200 disabled:opacity-50 text-sm whitespace-nowrap">
                {{ codeCooldown > 0 ? `${codeCooldown}s` : '发送验证码' }}
              </button>
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">新密码</label>
            <input v-model="newPassword" type="password" required minlength="6"
              class="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
          </div>
          <p v-if="error" class="text-red-500 text-sm">{{ error }}</p>
          <p v-if="success" class="text-green-600 text-sm">{{ success }}</p>
          <button type="submit" :disabled="loading"
            class="w-full bg-primary text-white py-2.5 rounded-lg hover:bg-primary-dark disabled:opacity-50 transition font-medium">
            {{ loading ? '重置中...' : '重置密码' }}
          </button>
        </form>
        <p class="text-center text-sm text-gray-500 mt-4">
          <button @click="switchMode('login')" class="text-primary hover:underline">返回登录</button>
        </p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const router = useRouter()
const route = useRoute()

const mode = ref<'login' | 'reset'>('login')
const email = ref('')
const password = ref('')
const code = ref('')
const newPassword = ref('')
const error = ref('')
const success = ref('')
const loading = ref(false)
const codeCooldown = ref(0)

function switchMode(m: 'login' | 'reset') {
  mode.value = m
  error.value = ''
  success.value = ''
}

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await userStore.login(email.value, password.value)
    router.push((route.query.redirect as string) || '/chat')
  } catch (e: any) {
    error.value = e?.message || '登录失败'
  } finally {
    loading.value = false
  }
}

async function handleSendCode() {
  if (!email.value) { error.value = '请先输入邮箱'; return }
  try {
    await userStore.sendCode(email.value)
    codeCooldown.value = 60
    const timer = setInterval(() => {
      codeCooldown.value--
      if (codeCooldown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e: any) {
    error.value = e?.message || '发送验证码失败'
  }
}

async function handleReset() {
  error.value = ''
  success.value = ''
  loading.value = true
  try {
    await userStore.resetPassword(email.value, code.value, newPassword.value)
    success.value = '密码重置成功，请登录'
    setTimeout(() => switchMode('login'), 1500)
  } catch (e: any) {
    error.value = e?.message || '重置密码失败'
  } finally {
    loading.value = false
  }
}
</script>
