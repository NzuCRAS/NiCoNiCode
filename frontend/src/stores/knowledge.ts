import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../services/api'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const docs = ref<any[]>([])
  const total = ref(0)
  const loading = ref(false)

  async function loadDocs(page = 1, size = 10, keyword = '', tag = '', categoryId?: number) {
    loading.value = true
    try {
      const params: any = { page, size, keyword, tag }
      if (categoryId !== undefined) params.categoryId = categoryId
      const res: any = await api.get('/knowledge/docs', { params })
      docs.value = res.data?.records || []
      total.value = res.data?.total || 0
    } finally {
      loading.value = false
    }
  }

  async function getDoc(id: number) {
    const res: any = await api.get(`/knowledge/docs/${id}`)
    return res.data
  }

  async function search(q: string, topK = 5) {
    const res: any = await api.get('/knowledge/search', { params: { q, topK } })
    return res.data || []
  }

  return { docs, total, loading, loadDocs, getDoc, search }
})
