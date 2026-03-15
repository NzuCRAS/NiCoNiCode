<template>
  <div class="min-h-[calc(100vh-3.5rem)] bg-gradient-to-br from-indigo-50 via-white to-purple-50 py-8 px-4">
    <div v-if="loading" class="text-center py-20 text-gray-400">加载中...</div>
    <div v-else-if="reports.length === 0" class="text-center py-20 text-gray-400">暂无报道</div>

    <div v-else class="newspaper">
      <!-- 报头 -->
      <div class="masthead">
        <h1 class="masthead-title">NICONICODE</h1>
        <div class="masthead-sub">技术追踪 · 深度分析</div>
        <div class="masthead-meta">
          <span>第 {{ issueNumber }} 期</span>
          <span>{{ todayStr }}</span>
          <span>niconicode.com</span>
        </div>
      </div>

      <div class="news-grid">
        <!-- #1 头条 (2列) -->
        <router-link v-if="reports[0]" :to="`/reports/${reports[0].id}`" class="story story-featured story-link">
          <h2 class="story-h2">{{ reports[0].title }}</h2>
          <div class="story-byline">
            <span v-if="reports[0].newVersion" class="badge-version">{{ reports[0].newVersion }}</span>
            <router-link v-if="getTechName(reports[0].trackedTechId)" :to="`/tech/${reports[0].trackedTechId}`"
              class="badge-tech" @click.stop>{{ getTechName(reports[0].trackedTechId) }}</router-link>
            <span>{{ formatDate(reports[0].publishedAt) }}</span>
            <span class="badge-score" :title="'技术指数 ' + (reports[0].techIndex || 500) + ' + 时间指数 ' + calcTimeIndex(reports[0].publishedAt)">
              {{ calcCompositeScore(reports[0]).toFixed(0) }}
            </span>
          </div>
          <p class="drop-cap">{{ stripContent(reports[0].content, 300) }}</p>
        </router-link>

        <!-- #11-15 简报 (1列) -->
        <div class="story" v-if="reports.length > 10">
          <h3 class="story-h3">本期简报</h3>
          <ul class="brief-list">
            <li v-for="r in reports.slice(10, 15)" :key="r.id">
              <router-link :to="`/reports/${r.id}`" class="brief-link">
                <strong>{{ r.title }}</strong>
                <span v-if="r.newVersion" class="badge-version-sm">{{ r.newVersion }}</span>
                <span class="badge-score-sm">{{ calcCompositeScore(r).toFixed(0) }}</span>
              </router-link>
            </li>
          </ul>
        </div>

        <!-- #2-4 三列卡片 -->
        <router-link v-for="r in reports.slice(1, 4)" :key="r.id" :to="`/reports/${r.id}`" class="story story-link">
          <h3 class="story-h3">{{ r.title }}</h3>
          <div class="story-byline">
            <span v-if="r.newVersion" class="badge-version">{{ r.newVersion }}</span>
            <router-link v-if="getTechName(r.trackedTechId)" :to="`/tech/${r.trackedTechId}`"
              class="badge-tech" @click.stop>{{ getTechName(r.trackedTechId) }}</router-link>
            <span>{{ formatDate(r.publishedAt) }}</span>
            <span class="badge-score" :title="'技术指数 ' + (r.techIndex || 500) + ' + 时间指数 ' + calcTimeIndex(r.publishedAt)">
              {{ calcCompositeScore(r).toFixed(0) }}
            </span>
          </div>
          <p>{{ stripContent(r.content, 120) }}</p>
        </router-link>

        <!-- #5 焦点 (2列) -->
        <router-link v-if="reports[4]" :to="`/reports/${reports[4].id}`" class="story story-featured story-link">
          <h3 class="story-h3">{{ reports[4].title }}</h3>
          <div class="story-byline">
            <span v-if="reports[4].newVersion" class="badge-version">{{ reports[4].newVersion }}</span>
            <router-link v-if="getTechName(reports[4].trackedTechId)" :to="`/tech/${reports[4].trackedTechId}`"
              class="badge-tech" @click.stop>{{ getTechName(reports[4].trackedTechId) }}</router-link>
            <span>{{ formatDate(reports[4].publishedAt) }}</span>
            <span class="badge-score" :title="'技术指数 ' + (reports[4].techIndex || 500) + ' + 时间指数 ' + calcTimeIndex(reports[4].publishedAt)">
              {{ calcCompositeScore(reports[4]).toFixed(0) }}
            </span>
          </div>
          <p>{{ stripContent(reports[4].content, 200) }}</p>
        </router-link>

        <!-- #6 卡片 -->
        <router-link v-if="reports[5]" :to="`/reports/${reports[5].id}`" class="story story-link">
          <h3 class="story-h3">{{ reports[5].title }}</h3>
          <div class="story-byline">
            <span v-if="reports[5].newVersion" class="badge-version">{{ reports[5].newVersion }}</span>
            <router-link v-if="getTechName(reports[5].trackedTechId)" :to="`/tech/${reports[5].trackedTechId}`"
              class="badge-tech" @click.stop>{{ getTechName(reports[5].trackedTechId) }}</router-link>
            <span>{{ formatDate(reports[5].publishedAt) }}</span>
            <span class="badge-score" :title="'技术指数 ' + (reports[5].techIndex || 500) + ' + 时间指数 ' + calcTimeIndex(reports[5].publishedAt)">
              {{ calcCompositeScore(reports[5]).toFixed(0) }}
            </span>
          </div>
          <p>{{ stripContent(reports[5].content, 120) }}</p>
        </router-link>

        <!-- #7-9 三列卡片 -->
        <router-link v-for="r in reports.slice(6, 9)" :key="r.id" :to="`/reports/${r.id}`" class="story story-link">
          <h3 class="story-h3">{{ r.title }}</h3>
          <div class="story-byline">
            <span v-if="r.newVersion" class="badge-version">{{ r.newVersion }}</span>
            <router-link v-if="getTechName(r.trackedTechId)" :to="`/tech/${r.trackedTechId}`"
              class="badge-tech" @click.stop>{{ getTechName(r.trackedTechId) }}</router-link>
            <span>{{ formatDate(r.publishedAt) }}</span>
            <span class="badge-score" :title="'技术指数 ' + (r.techIndex || 500) + ' + 时间指数 ' + calcTimeIndex(r.publishedAt)">
              {{ calcCompositeScore(r).toFixed(0) }}
            </span>
          </div>
          <p>{{ stripContent(r.content, 120) }}</p>
        </router-link>

        <!-- #10 深度分析 (全幅) -->
        <router-link v-if="reports[9]" :to="`/reports/${reports[9].id}`" class="story story-fullwidth story-link">
          <h3 class="story-h3 fullwidth-title">{{ reports[9].title }}</h3>
          <div class="story-byline">
            <span v-if="reports[9].newVersion" class="badge-version">{{ reports[9].newVersion }}</span>
            <router-link v-if="getTechName(reports[9].trackedTechId)" :to="`/tech/${reports[9].trackedTechId}`"
              class="badge-tech" @click.stop>{{ getTechName(reports[9].trackedTechId) }}</router-link>
            <span>{{ formatDate(reports[9].publishedAt) }}</span>
            <span class="badge-score" :title="'技术指数 ' + (reports[9].techIndex || 500) + ' + 时间指数 ' + calcTimeIndex(reports[9].publishedAt)">
              {{ calcCompositeScore(reports[9]).toFixed(0) }}
            </span>
          </div>
          <div class="multi-column">
            <p>{{ stripContent(reports[9].content, 250) }}</p>
          </div>
        </router-link>
      </div>

      <!-- 报脚 -->
      <div class="np-footer">
        <span>&copy; NiCoNiCode · 技术追踪平台</span>
        <span>每小时自动更新 · AI 驱动</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import api from '../services/api'

const reports = ref<any[]>([])
const techs = ref<any[]>([])
const loading = ref(true)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const todayStr = computed(() => {
  const d = new Date()
  const weekdays = ['日', '一', '二', '三', '四', '五', '六']
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 · 星期${weekdays[d.getDay()]}`
})

const issueNumber = computed(() => {
  const now = new Date()
  const start = new Date(now.getFullYear(), 0, 1)
  const diff = Math.floor((now.getTime() - start.getTime()) / 86400000)
  return `${now.getFullYear()}-${diff}`
})

function getTechName(techId: number | null): string {
  if (!techId) return ''
  const t = techs.value.find(t => t.id === techId)
  return t?.name || ''
}

function stripContent(content: string | null, maxLen: number): string {
  if (!content) return ''
  let text = content
    .replace(/```[\s\S]*?```/g, '')
    .replace(/#{1,6}\s+/g, '')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/[`~>|-]/g, '')
    .replace(/\n+/g, ' ')
    .trim()
  return text.length > maxLen ? text.substring(0, maxLen) + '...' : text
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}

/**
 * 计算时间指数: 满分 840，每小时降 5 分，最低 0
 */
function calcTimeIndex(publishedAt: string): number {
  if (!publishedAt) return 0
  const hours = (Date.now() - new Date(publishedAt).getTime()) / 3600000
  return Math.max(0, Math.round(840 - hours * 5))
}

/**
 * 计算综合指数: techIndex * 0.6 + timeIndex * 0.4
 */
function calcCompositeScore(report: any): number {
  const techIndex = report.techIndex ?? 500
  const timeIndex = calcTimeIndex(report.publishedAt)
  return techIndex * 0.6 + timeIndex * 0.4
}

async function fetchReports() {
  try {
    const res: any = await api.get('/tracker/reports/by-score', { params: { page: 1, size: 15 } })
    reports.value = res.data?.records || []
  } catch {
    try {
      const res: any = await api.get('/tracker/reports', { params: { page: 1, size: 15 } })
      reports.value = res.data?.records || []
    } catch { /* ignore */ }
  }
}

onMounted(async () => {
  try {
    const techsRes: any = await api.get('/tracker/techs')
    techs.value = techsRes.data || []
  } catch { /* ignore */ }

  await fetchReports()
  loading.value = false

  // 每小时自动刷新并按综合指数重排
  refreshTimer = setInterval(async () => {
    await fetchReports()
  }, 3600000) // 1 小时
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})
</script>

<style scoped>
.newspaper {
  max-width: 1200px;
  margin: 0 auto;
  background: white;
  border-radius: 1rem;
  box-shadow: 0 4px 24px rgba(0,0,0,0.08);
  padding: 2rem;
  border: 1px solid #e5e7eb;
}

.masthead {
  text-align: center;
  border-bottom: 3px double #6366f1;
  margin-bottom: 1.5rem;
  padding-bottom: 1rem;
}

.masthead-title {
  font-family: 'Georgia', 'Times New Roman', serif;
  font-size: 3rem;
  font-weight: 900;
  letter-spacing: 2px;
  text-transform: uppercase;
  line-height: 1.1;
  color: #1e1b4b;
  margin-bottom: 0.25rem;
}

.masthead-sub {
  font-size: 0.875rem;
  text-transform: uppercase;
  letter-spacing: 5px;
  color: #6366f1;
  border-top: 1px solid #c7d2fe;
  border-bottom: 1px solid #c7d2fe;
  display: inline-block;
  padding: 4px 0;
  margin: 0.5rem 0;
  font-weight: 500;
}

.masthead-meta {
  display: flex;
  justify-content: space-between;
  font-size: 0.8rem;
  font-family: 'Courier New', monospace;
  color: #6b7280;
  border-top: 1px dashed #d1d5db;
  padding-top: 0.5rem;
  margin-top: 0.5rem;
}

.news-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem 1.25rem;
  margin-bottom: 2rem;
}

.story {
  border-bottom: 1px solid #e5e7eb;
  padding-bottom: 1rem;
  break-inside: avoid;
}

.story-link {
  display: block;
  text-decoration: none;
  color: inherit;
  cursor: pointer;
  border-radius: 0.5rem;
  transition: background-color 0.2s;
}

.story-link:hover {
  background-color: #f5f3ff;
}

.story-featured {
  grid-column: span 2;
}

.story-fullwidth {
  grid-column: 1 / -1;
  border-top: 2px solid #6366f1;
  border-bottom: 2px solid #6366f1;
  padding: 1.25rem 0;
  margin-top: 0.5rem;
}

.story-h2 {
  font-family: 'Georgia', 'Times New Roman', serif;
  font-size: 1.75rem;
  font-weight: 700;
  line-height: 1.3;
  margin-bottom: 0.5rem;
  color: #1e1b4b;
  letter-spacing: -0.3px;
}

.story-h3 {
  font-family: 'Georgia', 'Times New Roman', serif;
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
  color: #1e1b4b;
  border-bottom: 1px dotted #c7d2fe;
  padding-bottom: 0.25rem;
}

.fullwidth-title {
  font-size: 1.5rem;
  border-bottom: 2px solid #6366f1;
  padding-bottom: 0.375rem;
}

.story-byline {
  font-size: 0.8rem;
  color: #6b7280;
  margin-bottom: 0.75rem;
  font-style: italic;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.story p {
  font-size: 0.95rem;
  margin-bottom: 0.75rem;
  text-align: justify;
  color: #374151;
  line-height: 1.7;
}

.drop-cap::first-letter {
  font-size: 3.5em;
  float: left;
  line-height: 0.8;
  margin-right: 0.5rem;
  color: #4338ca;
  font-weight: bold;
  font-family: 'Georgia', 'Times New Roman', serif;
}

.badge-version {
  background: #ecfdf5;
  color: #059669;
  padding: 1px 8px;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-style: normal;
}

.badge-version-sm {
  background: #ecfdf5;
  color: #059669;
  padding: 0 5px;
  border-radius: 9999px;
  font-size: 0.7rem;
  margin-left: 0.375rem;
}

.badge-tech {
  background: #eef2ff;
  color: #4f46e5;
  padding: 1px 8px;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-style: normal;
  text-decoration: none;
}

.badge-tech:hover {
  background: #c7d2fe;
}

/* 综合指数徽章 */
.badge-score {
  background: #fef3c7;
  color: #b45309;
  padding: 1px 8px;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-style: normal;
  font-weight: 600;
  cursor: help;
}

.badge-score-sm {
  background: #fef3c7;
  color: #b45309;
  padding: 0 5px;
  border-radius: 9999px;
  font-size: 0.7rem;
  font-weight: 600;
  margin-left: 0.375rem;
}

.multi-column {
  column-count: 2;
  column-gap: 1.75rem;
  column-rule: 1px solid #e5e7eb;
  text-align: justify;
  margin-top: 0.75rem;
}

.multi-column p {
  margin-bottom: 0.75rem;
  text-indent: 2em;
}

.brief-list {
  list-style: none;
  margin: 0.5rem 0;
  padding: 0;
}

.brief-list li {
  padding: 0.5rem 0 0.5rem 0.75rem;
  border-bottom: 1px dotted #d1d5db;
  font-size: 0.9rem;
  position: relative;
}

.brief-list li::before {
  content: '\25B8';
  color: #6366f1;
  font-weight: bold;
  position: absolute;
  left: 0;
}

.brief-list li:last-child {
  border-bottom: none;
}

.brief-link {
  text-decoration: none;
  color: inherit;
}

.brief-link:hover {
  text-decoration: underline;
  color: #4f46e5;
}

.brief-link strong {
  font-family: 'Georgia', 'Times New Roman', serif;
  margin-right: 0.375rem;
}

.np-footer {
  border-top: 3px double #6366f1;
  margin-top: 1.5rem;
  padding: 1rem 0 0.5rem 0;
  display: flex;
  justify-content: space-between;
  font-size: 0.75rem;
  font-family: 'Courier New', monospace;
  color: #6b7280;
}

@media (max-width: 800px) {
  .news-grid {
    grid-template-columns: 1fr;
  }
  .story-featured, .story-fullwidth {
    grid-column: span 1;
  }
  .multi-column {
    column-count: 1;
    column-rule: none;
  }
  .masthead-title {
    font-size: 2.25rem;
  }
}

@media (max-width: 500px) {
  .newspaper {
    padding: 1rem;
    border-radius: 0.75rem;
  }
}
</style>
