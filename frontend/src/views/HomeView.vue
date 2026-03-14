<template>
  <div class="min-h-[calc(100vh-3.5rem)]">
    <!-- Hero -->
    <section class="bg-gradient-to-br from-indigo-50 via-white to-purple-50 py-16 px-4">
      <div class="max-w-4xl mx-auto text-center">
        <h1 class="text-4xl font-bold text-gray-900 mb-4">Niconicode</h1>
        <p class="text-lg text-gray-600 mb-8">AI 驱动的技术追踪与智能问答平台，实时追踪开源项目动态，为你生成深度技术报道</p>
        <div class="flex justify-center space-x-4">
          <router-link to="/chat"
            class="bg-primary text-white px-6 py-2.5 rounded-lg hover:bg-primary-dark transition text-sm font-medium">
            开始对话
          </router-link>
          <router-link to="/reports"
            class="border border-gray-300 text-gray-700 px-6 py-2.5 rounded-lg hover:bg-gray-50 transition text-sm font-medium">
            浏览报道
          </router-link>
        </div>
      </div>
    </section>

    <!-- 追踪技术网格 -->
    <section class="max-w-6xl mx-auto py-12 px-4">
      <h2 class="text-xl font-semibold text-gray-800 mb-6">追踪中的技术</h2>
      <div v-if="loading" class="text-center text-gray-400 py-8">加载中...</div>
      <div v-else-if="techs.length === 0" class="text-center text-gray-400 py-8">暂无追踪技术</div>
      <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <router-link v-for="t in techs" :key="t.id" :to="`/tech/${t.id}`"
          class="block border border-gray-200 rounded-xl p-4 hover:border-primary hover:shadow-md transition bg-white group">
          <div class="flex items-start justify-between">
            <div>
              <h3 class="font-semibold text-gray-900 group-hover:text-primary transition">{{ t.name }}</h3>
              <span class="text-xs text-gray-400">{{ t.category }}</span>
            </div>
            <span v-if="t.lastKnownVersion"
              class="text-xs bg-indigo-50 text-indigo-600 px-2 py-0.5 rounded-full">{{ t.lastKnownVersion }}</span>
          </div>
          <div class="mt-3 flex items-center space-x-3 text-xs text-gray-400">
            <span v-if="t.githubRepo">{{ t.githubRepo }}</span>
            <span v-if="t.trackingMode && t.trackingMode !== 'RELEASE'"
              class="bg-yellow-50 text-yellow-600 px-1.5 py-0.5 rounded">{{ t.trackingMode }}</span>
          </div>
        </router-link>
      </div>
    </section>

    <!-- 最新报道 -->
    <section class="max-w-6xl mx-auto pb-12 px-4">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-xl font-semibold text-gray-800">最新报道</h2>
        <router-link to="/reports" class="text-sm text-primary hover:underline">查看全部</router-link>
      </div>
      <div v-if="latestReports.length === 0" class="text-center text-gray-400 py-8">暂无报道</div>
      <div v-else class="space-y-3">
        <router-link v-for="r in latestReports" :key="r.id" :to="`/reports/${r.id}`"
          class="block border border-gray-200 rounded-lg px-4 py-3 hover:border-primary hover:shadow-sm transition bg-white">
          <div class="flex items-center justify-between">
            <h3 class="text-sm font-medium text-gray-800 truncate flex-1 mr-4">{{ r.title }}</h3>
            <div class="flex items-center space-x-3 text-xs text-gray-400 shrink-0">
              <span v-if="r.newVersion" class="bg-green-50 text-green-600 px-1.5 py-0.5 rounded">{{ r.newVersion }}</span>
              <span>{{ formatDate(r.publishedAt) }}</span>
            </div>
          </div>
        </router-link>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../services/api'

const techs = ref<any[]>([])
const latestReports = ref<any[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    const [techsRes, reportsRes] = await Promise.all([
      api.get('/tracker/techs'),
      api.get('/tracker/reports/latest', { params: { limit: 5 } })
    ]) as [any, any]
    techs.value = (techsRes.data || techsRes || []).filter((t: any) => t.status === 'ACTIVE')
    latestReports.value = reportsRes.data || reportsRes || []
  } catch { /* ignore */ }
  finally {
    loading.value = false
  }
})

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}
</script>
