<template>
  <div class="max-w-6xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-6">知识库</h1>

    <!-- Search -->
    <div class="flex space-x-3 mb-4">
      <input v-model="keyword" @keydown.enter="search" placeholder="搜索知识文档..."
        class="flex-1 border border-gray-300 rounded-lg px-4 py-2 text-sm focus:outline-none focus:border-primary" />
      <button @click="search" class="bg-primary text-white px-5 py-2 rounded-lg hover:bg-primary-dark text-sm">搜索</button>
    </div>

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

    <div v-if="knowledgeStore.loading" class="text-center py-12 text-gray-400">加载中...</div>
    <div v-else-if="knowledgeStore.docs.length === 0" class="text-center py-12 text-gray-400">暂无文档</div>
    <div v-else class="space-y-4">
      <router-link v-for="doc in knowledgeStore.docs" :key="doc.id" :to="`/knowledge/${doc.id}`"
        class="block bg-white p-5 rounded-xl border border-gray-200 hover:shadow-md transition">
        <div class="flex items-center space-x-2 mb-2">
          <span :class="['text-xs px-2 py-0.5 rounded-full', sourceTypeStyle(doc.sourceType)]">
            {{ sourceTypeLabel(doc.sourceType) }}
          </span>
          <span class="text-xs text-gray-400">{{ formatDate(doc.createdAt) }}</span>
          <span class="text-xs text-gray-400">{{ doc.viewCount }} 次查看</span>
        </div>
        <h3 class="font-semibold text-gray-900 mb-1">{{ doc.title }}</h3>
        <p class="text-sm text-gray-500 line-clamp-2">{{ stripMarkdown(doc.content || '').substring(0, 200) }}</p>
        <div v-if="doc.tags" class="mt-2 flex flex-wrap gap-1">
          <span v-for="tag in doc.tags.split(',')" :key="tag"
            class="text-xs bg-gray-100 text-gray-600 px-2 py-0.5 rounded">{{ tag.trim() }}</span>
        </div>
      </router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../services/api'
import { useKnowledgeStore } from '../stores/knowledge'
import { stripMarkdown } from '../utils/text'

const knowledgeStore = useKnowledgeStore()
const keyword = ref('')
const categories = ref<any[]>([])
const selectedCategoryId = ref<number | null>(null)

onMounted(async () => {
  try {
    const res: any = await api.get('/categories')
    categories.value = res.data || []
  } catch { /* ignore */ }
  knowledgeStore.loadDocs()
})

function filterByCategory(categoryId: number | null) {
  selectedCategoryId.value = categoryId
  knowledgeStore.loadDocs(1, 10, keyword.value, undefined, categoryId ?? undefined)
}

function search() {
  knowledgeStore.loadDocs(1, 10, keyword.value, undefined, selectedCategoryId.value ?? undefined)
}

function sourceTypeLabel(type: string) {
  const map: Record<string, string> = { CHAT_SUMMARY: '对话精华', TRACKER_REPORT: '技术报道', MANUAL: '手动创建' }
  return map[type] || type
}

function sourceTypeStyle(type: string) {
  const map: Record<string, string> = {
    CHAT_SUMMARY: 'bg-blue-100 text-blue-700',
    TRACKER_REPORT: 'bg-green-100 text-green-700',
    MANUAL: 'bg-gray-100 text-gray-700'
  }
  return map[type] || 'bg-gray-100 text-gray-700'
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}
</script>
