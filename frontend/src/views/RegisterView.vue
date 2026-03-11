<template>
  <div class="min-h-[calc(100vh-3.5rem)] flex items-center justify-center bg-gray-50 px-4">
    <div class="bg-white p-8 rounded-2xl shadow-sm border border-gray-200 w-full max-w-md">
      <h2 class="text-2xl font-bold text-center mb-6">注册</h2>
      <form @submit.prevent="handleRegister" class="space-y-4">
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
          <label class="block text-sm font-medium text-gray-700 mb-1">密码</label>
          <input v-model="password" type="password" required minlength="6"
            class="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">昵称（可选）</label>
          <input v-model="nickname" type="text"
            class="w-full border border-gray-300 rounded-lg px-4 py-2.5 text-sm focus:outline-none focus:border-primary" />
        </div>
        <p v-if="error" class="text-red-500 text-sm">{{ error }}</p>
        <button type="submit" :disabled="loading"
          class="w-full bg-primary text-white py-2.5 rounded-lg hover:bg-primary-dark disabled:opacity-50 transition font-medium">
          {{ loading ? '注册中...' : '注册' }}
        </button>
      </form>
      <p class="text-center text-sm text-gray-500 mt-4">
        已有账号？<router-link to="/login" class="text-primary hover:underline">登录</router-link>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const router = useRouter()
const email = ref('')
const code = ref('')
const password = ref('')
const nickname = ref('')
const error = ref('')
const loading = ref(false)
const codeCooldown = ref(0)

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

async function handleRegister() {
  error.value = ''
  loading.value = true
  try {
    await userStore.register(email.value, code.value, password.value, nickname.value)
    router.push('/chat')
  } catch (e: any) {
    error.value = e?.message || '注册失败'
  } finally {
    loading.value = false
  }
}
</script>
