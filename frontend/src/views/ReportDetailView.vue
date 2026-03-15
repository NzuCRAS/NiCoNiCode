<template>
  <div class="max-w-4xl mx-auto py-8 px-4">
    <router-link to="/reports" class="text-sm text-primary hover:underline mb-4 inline-block">&larr; 返回报道列表</router-link>
    <div v-if="loading" class="text-center py-12 text-gray-400">加载中...</div>
    <article v-else-if="report" class="bg-white p-8 rounded-xl border border-gray-200">
      <h1 class="text-2xl font-bold mb-3">{{ report.title }}</h1>
      <div class="flex items-center space-x-3 mb-6 text-sm text-gray-500 flex-wrap">
        <span v-if="report.newVersion" class="bg-green-100 text-green-700 px-2 py-0.5 rounded-full">
          {{ report.newVersion }}
        </span>
        <span v-if="report.publishedAt">发布: {{ formatDate(report.publishedAt) }}</span>
        <span v-if="report.createdAt && report.createdAt !== report.publishedAt">创建: {{ formatDate(report.createdAt) }}</span>
        <span v-if="report.updatedAt && report.updatedAt !== report.publishedAt">更新: {{ formatDate(report.updatedAt) }}</span>
        <span class="bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full font-semibold"
          :title="'技术指数 ' + (report.techIndex || 500) + ' × 0.6 + 时间指数 ' + calcTimeIndex(report.publishedAt) + ' × 0.4'">
          综合指数: {{ calcCompositeScore(report).toFixed(0) }}
        </span>
      </div>
      <div class="markdown-body" v-html="renderedContent"></div>
    </article>

    <!-- 勘误区 -->
    <section v-if="report" class="mt-8 bg-white p-6 rounded-xl border border-gray-200">
      <h2 class="text-lg font-semibold mb-4">勘误</h2>
      <div v-if="errataList.length > 0" class="space-y-3 mb-4">
        <div v-for="e in errataList" :key="e.id" class="border border-gray-100 rounded-lg p-3">
          <div class="flex items-center space-x-2 mb-1">
            <span class="text-sm font-medium text-gray-700">{{ e.userNickname }}</span>
            <span :class="['text-xs px-2 py-0.5 rounded-full', statusStyle(e.status)]">{{ statusLabel(e.status) }}</span>
            <span class="text-xs text-gray-400">{{ formatDate(e.createdAt) }}</span>
          </div>
          <p class="text-sm text-gray-600">{{ e.content }}</p>
          <p v-if="e.adminNote" class="text-xs text-gray-400 mt-1">管理员备注: {{ e.adminNote }}</p>
        </div>
      </div>
      <div v-if="userStore.isLoggedIn" :class="[errataList.length > 0 ? 'border-t pt-4' : '']">
        <textarea v-model="errataContent" rows="3" placeholder="提交勘误内容..."
          class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:border-primary"></textarea>
        <button @click="submitErrata" :disabled="!errataContent.trim() || submitting"
          class="mt-2 bg-primary text-white px-4 py-1.5 rounded-lg text-sm hover:bg-primary-dark disabled:opacity-50">
          {{ submitting ? '提交中...' : '提交勘误' }}
        </button>
      </div>
      <p v-else :class="[errataList.length > 0 ? 'border-t pt-4' : '', 'text-sm text-gray-400']">
        <router-link to="/login" class="text-primary hover:underline">登录</router-link> 后可提交勘误
      </p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '../stores/user'
import api from '../services/api'
import { marked } from 'marked'
import { cleanMarkdown } from '../utils/text'

const route = useRoute()
const userStore = useUserStore()
const report = ref<any>(null)
const loading = ref(true)
const errataList = ref<any[]>([])
const errataContent = ref('')
const submitting = ref(false)

const renderedContent = computed(() => {
  if (!report.value?.content) return ''
  try { return marked.parse(cleanMarkdown(report.value.content)) as string } catch { return report.value.content }
})

onMounted(async () => {
  try {
    const res: any = await api.get(`/tracker/reports/${route.params.id}`)
    report.value = res.data
    await loadErrata()
  } finally {
    loading.value = false
  }
})

async function loadErrata() {
  try {
    const res: any = await api.get('/errata/doc', { params: { docType: 'TECH_REPORT', docId: route.params.id } })
    errataList.value = res.data || []
  } catch { /* ignore */ }
}

async function submitErrata() {
  if (!errataContent.value.trim()) return
  submitting.value = true
  try {
    await api.post('/errata', { docType: 'TECH_REPORT', docId: Number(route.params.id), content: errataContent.value })
    errataContent.value = ''
    await loadErrata()
  } finally {
    submitting.value = false
  }
}

function statusLabel(s: string) {
  const map: Record<string, string> = { PENDING: '待审核', ACCEPTED: '已采纳', REJECTED: '已驳回' }
  return map[s] || s
}

function statusStyle(s: string) {
  const map: Record<string, string> = {
    PENDING: 'bg-yellow-100 text-yellow-700',
    ACCEPTED: 'bg-green-100 text-green-700',
    REJECTED: 'bg-red-100 text-red-700'
  }
  return map[s] || 'bg-gray-100 text-gray-700'
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}

function calcTimeIndex(publishedAt: string): number {
  if (!publishedAt) return 0
  const hours = (Date.now() - new Date(publishedAt).getTime()) / 3600000
  return Math.max(0, Math.round(840 - hours * 5))
}

function calcCompositeScore(report: any): number {
  const techIndex = report.techIndex ?? 500
  const timeIndex = calcTimeIndex(report.publishedAt)
  return techIndex * 0.6 + timeIndex * 0.4
}
</script>
