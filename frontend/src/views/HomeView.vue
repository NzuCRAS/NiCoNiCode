<template>
  <div>
    <!-- Hero Section -->
    <section class="bg-gradient-to-br from-indigo-50 to-white py-20">
      <div class="max-w-4xl mx-auto text-center px-4">
        <h1 class="text-4xl font-bold text-gray-900 mb-4">NiCoNiCode</h1>
        <p class="text-lg text-gray-600 mb-8">AI 驱动的技术问答、知识库与热点技术追踪平台</p>
        <div class="flex justify-center space-x-4">
          <router-link to="/chat" class="bg-primary text-white px-6 py-3 rounded-lg hover:bg-primary-dark transition font-medium">
            开始对话
          </router-link>
          <router-link to="/reports" class="border border-primary text-primary px-6 py-3 rounded-lg hover:bg-indigo-50 transition font-medium">
            查看报道
          </router-link>
        </div>
      </div>
    </section>

    <!-- Features -->
    <section class="max-w-6xl mx-auto py-16 px-4">
      <div class="grid grid-cols-1 md:grid-cols-3 gap-8">
        <div class="bg-white p-6 rounded-xl border border-gray-200 hover:shadow-lg transition">
          <div class="text-3xl mb-3">💬</div>
          <h3 class="text-lg font-semibold mb-2">AI 技术问答</h3>
          <p class="text-gray-600 text-sm">基于 DeepSeek V3 的智能对话，支持 RAG 知识增强、流式回复和上下文记忆。</p>
        </div>
        <div class="bg-white p-6 rounded-xl border border-gray-200 hover:shadow-lg transition">
          <div class="text-3xl mb-3">📡</div>
          <h3 class="text-lg font-semibold mb-2">狗仔 Agent</h3>
          <p class="text-gray-600 text-sm">自动追踪主流技术动态，监控 GitHub Release、RSS 更新，AI 生成技术报道。</p>
        </div>
        <div class="bg-white p-6 rounded-xl border border-gray-200 hover:shadow-lg transition">
          <div class="text-3xl mb-3">📚</div>
          <h3 class="text-lg font-semibold mb-2">知识库</h3>
          <p class="text-gray-600 text-sm">自动积累技术知识，支持语义搜索，对话精华和追踪报道自动入库。</p>
        </div>
      </div>
    </section>

    <!-- Latest Reports -->
    <section class="max-w-6xl mx-auto pb-16 px-4" v-if="latestReports.length">
      <h2 class="text-2xl font-bold mb-6">最新技术报道</h2>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <router-link v-for="report in latestReports" :key="report.id" :to="`/reports/${report.id}`"
          class="bg-white p-5 rounded-xl border border-gray-200 hover:shadow-md transition block">
          <h3 class="font-semibold text-gray-900 mb-2">{{ report.title }}</h3>
          <p class="text-sm text-gray-500 mb-3">{{ stripMarkdown(report.changeSummary || '').substring(0, 100) }}...</p>
          <span class="text-xs text-gray-400">{{ formatDate(report.publishedAt) }}</span>
        </router-link>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../services/api'
import { stripMarkdown } from '../utils/text'

const latestReports = ref<any[]>([])

onMounted(async () => {
  try {
    const res: any = await api.get('/tracker/reports/latest', { params: { limit: 3 } })
    latestReports.value = res.data || []
  } catch { /* ignore */ }
})

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}
</script>
