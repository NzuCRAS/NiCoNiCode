<template>
  <div class="min-h-screen flex flex-col">
    <nav class="bg-white border-b border-gray-200 sticky top-0 z-50">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between h-14 items-center">
          <div class="flex items-center space-x-8">
            <router-link to="/" class="text-xl font-bold text-primary">NiCoNiCode</router-link>
            <div class="hidden sm:flex space-x-6">
              <router-link to="/" class="nav-link">首页</router-link>
              <router-link to="/chat" class="nav-link">AI 对话</router-link>
              <router-link to="/reports" class="nav-link">技术报道</router-link>
              <router-link to="/knowledge" class="nav-link">知识库</router-link>
              <router-link v-if="userStore.role === 'ADMIN'" to="/admin" class="nav-link">管理后台</router-link>
            </div>
          </div>
          <div class="flex items-center space-x-4">
            <template v-if="userStore.isLoggedIn">
              <span class="text-sm text-gray-600">{{ userStore.nickname || userStore.email }}</span>
              <button @click="handleLogout" class="text-sm text-gray-500 hover:text-gray-700">退出</button>
            </template>
            <template v-else>
              <router-link to="/login" class="text-sm text-primary hover:text-primary-dark">登录</router-link>
              <router-link to="/register" class="btn-primary text-sm">注册</router-link>
            </template>
          </div>
        </div>
      </div>
    </nav>
    <main class="flex-1">
      <router-view />
    </main>
    <footer class="bg-white border-t border-gray-200 py-6 text-center text-sm text-gray-400">
      NiCoNiCode &copy; 2026 - AI 驱动的技术问答与追踪平台
    </footer>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useUserStore } from './stores/user'
import { useRouter } from 'vue-router'

const userStore = useUserStore()
const router = useRouter()

onMounted(() => {
  userStore.fetchMe()
})

function handleLogout() {
  userStore.logout()
  router.push('/')
}
</script>

<style>
.nav-link {
  @apply text-sm text-gray-600 hover:text-primary transition-colors;
}
.nav-link.router-link-active {
  @apply text-primary font-medium;
}
.btn-primary {
  @apply bg-primary text-white px-4 py-1.5 rounded-lg hover:bg-primary-dark transition-colors;
}
</style>
