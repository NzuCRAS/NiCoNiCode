<template>
  <div class="min-h-screen flex flex-col" :style="appStyle">
    <nav class="border-b sticky top-0 z-50" :style="navStyle">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between h-14 items-center">
          <div class="flex items-center space-x-8">
            <router-link to="/" class="text-xl font-bold text-primary">Nicode</router-link>
            <div class="hidden sm:flex space-x-6">
              <router-link to="/" class="nav-link">首页</router-link>
              <router-link to="/chat" class="nav-link">AI 对话</router-link>
              <router-link to="/reports" class="nav-link">技术报道</router-link>
              <router-link to="/knowledge" class="nav-link">知识库</router-link>
              <router-link v-if="userStore.role === 'ADMIN'" to="/admin" class="nav-link">管理后台</router-link>
            </div>
          </div>
          <div class="flex items-center space-x-4">
            <!-- 设置按钮 -->
            <button @click="showSettings = !showSettings"
              class="text-gray-500 hover:text-gray-700 transition p-1 rounded-lg hover:bg-gray-100"
              title="外观设置">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
            </button>
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

    <!-- 外观设置面板 -->
    <transition name="slide">
      <div v-if="showSettings" class="fixed right-0 top-14 w-72 bg-white border-l border-b border-gray-200 shadow-lg z-40 rounded-bl-xl">
        <div class="p-4">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-sm font-semibold text-gray-800">外观设置</h3>
            <button @click="showSettings = false" class="text-gray-400 hover:text-gray-600">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <!-- 字体选择 -->
          <div class="mb-4">
            <label class="text-xs text-gray-500 mb-1.5 block">字体</label>
            <select v-model="settings.fontFamily" @change="applySettings"
              class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary">
              <option value="system">系统默认</option>
              <option value="'Noto Sans SC', sans-serif">思源黑体</option>
              <option value="'Noto Serif SC', serif">思源宋体</option>
              <option value="Georgia, serif">Georgia</option>
              <option value="'Courier New', monospace">等宽字体</option>
              <option value="'Microsoft YaHei', sans-serif">微软雅黑</option>
              <option value="'PingFang SC', sans-serif">苹方</option>
            </select>
          </div>

          <!-- 背景色选择 -->
          <div class="mb-4">
            <label class="text-xs text-gray-500 mb-1.5 block">背景色</label>
            <div class="grid grid-cols-5 gap-2">
              <button v-for="color in bgColors" :key="color.value"
                @click="settings.bgColor = color.value; applySettings()"
                :style="{ backgroundColor: color.value }"
                :class="['w-8 h-8 rounded-lg border-2 transition-all', settings.bgColor === color.value ? 'border-primary scale-110 shadow-md' : 'border-gray-200 hover:border-gray-400']"
                :title="color.label">
              </button>
            </div>
          </div>

          <!-- 自定义背景色 -->
          <div class="mb-4">
            <label class="text-xs text-gray-500 mb-1.5 block">自定义背景色</label>
            <div class="flex space-x-2">
              <input type="color" v-model="settings.bgColor" @input="applySettings"
                class="w-10 h-8 rounded border border-gray-300 cursor-pointer" />
              <input type="text" v-model="settings.bgColor" @change="applySettings"
                class="flex-1 border border-gray-300 rounded-lg px-3 py-1.5 text-xs font-mono focus:outline-none focus:border-primary"
                placeholder="#f9fafb" />
            </div>
          </div>

          <!-- 重置按钮 -->
          <button @click="resetSettings"
            class="w-full text-center text-xs text-gray-500 hover:text-primary border border-gray-200 rounded-lg py-1.5 hover:border-primary transition">
            恢复默认设置
          </button>
        </div>
      </div>
    </transition>

    <main class="flex-1">
      <router-view />
    </main>
    <footer class="border-t py-6 text-center text-sm text-gray-400" :style="navStyle">
      Nicode &copy; 2026 - AI 驱动的技术问答与追踪平台
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useUserStore } from './stores/user'
import { useRouter } from 'vue-router'

const userStore = useUserStore()
const router = useRouter()
const showSettings = ref(false)

const bgColors = [
  { value: '#f9fafb', label: '浅灰 (默认)' },
  { value: '#ffffff', label: '纯白' },
  { value: '#fffbeb', label: '暖黄' },
  { value: '#f0fdf4', label: '浅绿' },
  { value: '#eff6ff', label: '浅蓝' },
  { value: '#faf5ff', label: '浅紫' },
  { value: '#fff1f2', label: '浅粉' },
  { value: '#f5f5f4', label: '石灰' },
  { value: '#fefce8', label: '米色' },
  { value: '#1e1b4b', label: '深色' },
]

const DEFAULT_FONT = "system"
const DEFAULT_BG = "#f9fafb"

const settings = reactive({
  fontFamily: DEFAULT_FONT,
  bgColor: DEFAULT_BG,
})

const systemFont = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif"

const appStyle = computed(() => ({
  fontFamily: settings.fontFamily === 'system' ? systemFont : settings.fontFamily,
  backgroundColor: settings.bgColor,
  color: isLightBg.value ? '#111827' : '#f3f4f6',
}))

const navStyle = computed(() => ({
  backgroundColor: isLightBg.value ? '#ffffff' : '#1f2937',
  borderColor: isLightBg.value ? '#e5e7eb' : '#374151',
}))

const isLightBg = computed(() => {
  const hex = settings.bgColor.replace('#', '')
  const r = parseInt(hex.substring(0, 2), 16)
  const g = parseInt(hex.substring(2, 4), 16)
  const b = parseInt(hex.substring(4, 6), 16)
  const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255
  return luminance > 0.5
})

function applySettings() {
  localStorage.setItem('nico-appearance', JSON.stringify(settings))
  document.body.style.backgroundColor = settings.bgColor
}

function resetSettings() {
  settings.fontFamily = DEFAULT_FONT
  settings.bgColor = DEFAULT_BG
  applySettings()
}

function loadSettings() {
  const saved = localStorage.getItem('nico-appearance')
  if (saved) {
    try {
      const parsed = JSON.parse(saved)
      if (parsed.fontFamily) settings.fontFamily = parsed.fontFamily
      if (parsed.bgColor) settings.bgColor = parsed.bgColor
    } catch { /* ignore */ }
  }
  document.body.style.backgroundColor = settings.bgColor
}

onMounted(() => {
  userStore.fetchMe()
  loadSettings()
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

/* 设置面板滑入动画 */
.slide-enter-active, .slide-leave-active {
  transition: transform 0.2s ease, opacity 0.2s ease;
}
.slide-enter-from, .slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>
