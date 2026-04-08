<template>
  <div class="min-h-[calc(100vh-3.5rem)]">
    <!-- Hero -->
    <section class="bg-gradient-to-br from-indigo-50 via-white to-purple-50 py-16 px-4">
      <div class="max-w-4xl mx-auto text-center">
        <h1 class="text-4xl font-bold text-gray-900 mb-4">Nicode</h1>
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

  <!-- 知识图谱（语义网络：nodes + edges） -->
    <section class="max-w-6xl mx-auto py-12 px-4">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-xl font-semibold text-gray-800">知识图谱</h2>
        <router-link to="/reports" class="text-sm text-primary hover:underline">浏览全部报道</router-link>
      </div>

      <div v-if="loading" class="text-center text-gray-400 py-8">加载中...</div>
      <div v-else-if="network.nodes.length === 0" class="text-center text-gray-400 py-8">暂无数据</div>

      <div v-else class="space-y-4">
        <TrackerNetworkGraph :nodes="network.nodes" :edges="network.edges" />

        <!-- fallback: 若未来需要对照旧版树列表，可重新启用 /tracker/graph 并渲染在这里 -->
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../services/api'
import TrackerNetworkGraph from '../components/TrackerNetworkGraph.vue'

const network = ref<{ nodes: any[]; edges: any[] }>({ nodes: [], edges: [] })
const loading = ref(true)

onMounted(async () => {
  try {
    const res: any = await api.get('/tracker/graph/network', {
      params: { reportsPerTech: 5, maxTechs: 30, includeTechCooccurrence: true }
    })
    network.value = res.data || { nodes: [], edges: [] }
  } catch { /* ignore */ }
  finally {
    loading.value = false
  }
})
</script>
