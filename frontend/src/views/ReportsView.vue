<template>
  <div class="max-w-6xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-6">技术报道</h1>

    <!-- 分类过滤 -->
    <div v-if="categories.length" class="flex flex-wrap gap-2 mb-6">
      <button @click="filterByCategory(null)"
        :class="['px-3 py-1 rounded-full text-sm transition', selectedCategoryId === null ? 'bg-primary text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200']">
        全部
      </button>
      <button v-for="cat in categories" :key="cat.id" @click="filterByCategory(cat.id)"
        :class="['px-3 py-1 rounded-full text-sm transition', selectedCategoryId === cat.id ? 'bg-primary text-white' : 'bg-gray-100 text-gray-600 hover:bg-gray-200']">
        {{ cat.name }}
      </button>
    </div>

    <div v-if="loading" class="text-center py-12 text-gray-400">加载中...</div>
    <div v-else-if="reports.length === 0" class="text-center py-12 text-gray-400">暂无报道</div>
    <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-6">
      <router-link v-for="report in reports" :key="report.id" :to="`/reports/${report.id}`"
        class="bg-white p-5 rounded-xl border border-gray-200 hover:shadow-md transition block">
        <div class="flex items-center space-x-2 mb-2">
          <span v-if="report.newVersion" class="text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full">
            {{ report.newVersion }}
          </span>
          <span class="text-xs text-gray-400">{{ formatDate(report.publishedAt) }}</span>
        </div>
        <h3 class="font-semibold text-gray-900 mb-2">{{ report.title }}</h3>
        <p class="text-sm text-gray-500 line-clamp-3">{{ stripMarkdown(report.changeSummary || report.content || '').substring(0, 150) }}</p>
      </router-link>
    </div>
    <!-- Pagination -->
    <div v-if="totalPages > 1" class="flex justify-center mt-8 space-x-2">
      <button v-for="p in totalPages" :key="p" @click="loadPage(p)"
        :class="['px-3 py-1.5 rounded text-sm', page === p ? 'bg-primary text-white' : 'bg-gray-100 hover:bg-gray-200']">
        {{ p }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../services/api'
import { stripMarkdown } from '../utils/text'

const reports = ref<any[]>([])
const categories = ref<any[]>([])
const loading = ref(true)
const page = ref(1)
const totalPages = ref(1)
const selectedCategoryId = ref<number | null>(null)

onMounted(async () => {
  try {
    const res: any = await api.get('/categories')
    categories.value = res.data || []
  } catch { /* ignore */ }
  loadPage(1)
})

function filterByCategory(categoryId: number | null) {
  selectedCategoryId.value = categoryId
  loadPage(1)
}

async function loadPage(p: number) {
  loading.value = true
  page.value = p
  try {
    const params: any = { page: p, size: 10 }
    if (selectedCategoryId.value !== null) params.categoryId = selectedCategoryId.value
    const res: any = await api.get('/tracker/reports', { params })
    reports.value = res.data?.records || []
    totalPages.value = Math.ceil((res.data?.total || 0) / 10)
  } finally {
    loading.value = false
  }
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}
</script>
