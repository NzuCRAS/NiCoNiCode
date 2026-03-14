<template>
  <div class="max-w-6xl mx-auto py-8 px-4">
    <!-- 加载状态 -->
    <div v-if="loading" class="text-center py-12 text-gray-400">加载中...</div>
    <div v-else-if="!tech" class="text-center py-12 text-gray-400">技术不存在</div>

    <template v-else>
      <!-- 技术信息卡片 -->
      <div class="bg-white rounded-xl border border-gray-200 p-6 mb-8">
        <div class="flex items-start justify-between">
          <div>
            <h1 class="text-2xl font-bold text-gray-900">{{ tech.name }}</h1>
            <div class="flex items-center space-x-3 mt-2">
              <span v-if="tech.category" class="text-sm bg-indigo-100 text-indigo-700 px-2.5 py-0.5 rounded-full">{{ tech.category }}</span>
              <span v-if="tech.lastKnownVersion" class="text-sm bg-green-100 text-green-700 px-2.5 py-0.5 rounded-full">{{ tech.lastKnownVersion }}</span>
              <span :class="['text-xs px-2 py-0.5 rounded-full', tech.status === 'ACTIVE' ? 'bg-green-50 text-green-600' : 'bg-gray-100 text-gray-500']">
                {{ tech.status === 'ACTIVE' ? '追踪中' : '已暂停' }}
              </span>
            </div>
          </div>
          <div class="flex space-x-2">
            <a v-if="tech.githubRepo" :href="'https://github.com/' + tech.githubRepo" target="_blank"
              class="text-sm text-gray-500 hover:text-gray-800 border border-gray-300 px-3 py-1.5 rounded-lg hover:bg-gray-50 transition">
              GitHub
            </a>
            <a v-if="tech.officialUrl" :href="tech.officialUrl" target="_blank"
              class="text-sm text-gray-500 hover:text-gray-800 border border-gray-300 px-3 py-1.5 rounded-lg hover:bg-gray-50 transition">
              官网
            </a>
          </div>
        </div>
        <div v-if="tech.lastCheckedAt" class="mt-3 text-xs text-gray-400">
          上次检查: {{ formatDateTime(tech.lastCheckedAt) }}
        </div>
      </div>

      <!-- 主体内容 -->
      <div class="flex gap-8">
        <!-- 左栏：更新时间线 -->
        <div class="flex-1 min-w-0" style="flex: 2;">
          <h2 class="text-lg font-semibold text-gray-800 mb-4">更新时间线</h2>
          <div v-if="reports.length === 0" class="text-gray-400 text-sm py-4">暂无更新报道</div>
          <div v-else class="relative pl-6">
            <!-- 竖线 -->
            <div class="absolute left-2 top-2 bottom-2 w-0.5 bg-gray-200"></div>
            <!-- 报道卡片 -->
            <div v-for="report in reports" :key="report.id" class="relative mb-6">
              <!-- 圆点 -->
              <div class="absolute -left-4 top-3 w-3 h-3 rounded-full bg-primary border-2 border-white shadow"></div>
              <router-link :to="`/reports/${report.id}`"
                class="block bg-white rounded-xl border border-gray-200 p-4 hover:shadow-md transition">
                <div class="flex items-center space-x-2 mb-2">
                  <span v-if="report.newVersion" class="text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full">
                    {{ report.newVersion }}
                  </span>
                  <span class="text-xs text-gray-400">{{ formatDate(report.publishedAt) }}</span>
                </div>
                <h3 class="font-semibold text-gray-900 mb-1">{{ report.title }}</h3>
                <p class="text-sm text-gray-500 line-clamp-2">{{ stripMarkdown(report.content || '').substring(0, 120) }}</p>
              </router-link>
            </div>
          </div>
        </div>

        <!-- 右栏：关联知识文档 -->
        <div class="w-80 flex-shrink-0">
          <h2 class="text-lg font-semibold text-gray-800 mb-4">关联知识文档</h2>
          <div v-if="knowledgeDocs.length === 0" class="text-gray-400 text-sm py-4">暂无关联文档</div>
          <div v-else class="space-y-3">
            <router-link v-for="doc in knowledgeDocs" :key="doc.id" :to="`/knowledge/${doc.id}`"
              class="block bg-white rounded-lg border border-gray-200 p-3 hover:shadow-sm transition">
              <h4 class="text-sm font-medium text-gray-900 mb-1 line-clamp-2">{{ doc.title }}</h4>
              <div class="flex flex-wrap gap-1 mb-1">
                <span v-for="tag in parseTags(doc.tags)" :key="tag"
                  class="text-xs bg-gray-100 text-gray-600 px-1.5 py-0.5 rounded">{{ tag }}</span>
              </div>
              <span class="text-xs text-gray-400">{{ formatDate(doc.createdAt) }}</span>
            </router-link>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '../services/api'
import { stripMarkdown } from '../utils/text'

const route = useRoute()
const techId = Number(route.params.id)
const loading = ref(true)
const tech = ref<any>(null)
const reports = ref<any[]>([])
const knowledgeDocs = ref<any[]>([])

onMounted(async () => {
  try {
    const [techRes, reportsRes, knowledgeRes] = await Promise.all([
      api.get(`/tracker/techs/${techId}`) as any,
      api.get(`/tracker/techs/${techId}/reports`, { params: { page: 1, size: 50 } }) as any,
      api.get(`/tracker/techs/${techId}/knowledge`) as any
    ])
    tech.value = techRes.data
    reports.value = reportsRes.data?.records || []
    knowledgeDocs.value = knowledgeRes.data || []
  } catch { /* ignore */ } finally {
    loading.value = false
  }
})

function parseTags(tags: string): string[] {
  if (!tags) return []
  return tags.split(',').map(t => t.trim()).filter(Boolean)
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}

function formatDateTime(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleString('zh-CN')
}
</script>
