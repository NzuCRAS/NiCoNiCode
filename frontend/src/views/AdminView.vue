<template>
  <div class="max-w-7xl mx-auto py-8 px-4">
    <h1 class="text-2xl font-bold mb-6">管理后台</h1>

    <!-- Tabs -->
    <div class="flex border-b border-gray-200 mb-6">
      <button v-for="tab in tabs" :key="tab.key" @click="activeTab = tab.key"
        :class="['px-4 py-2 text-sm font-medium border-b-2 -mb-px transition',
          activeTab === tab.key ? 'border-primary text-primary' : 'border-transparent text-gray-500 hover:text-gray-700']">
        {{ tab.label }}
      </button>
    </div>

    <!-- 报道管理 -->
    <div v-if="activeTab === 'reports'">
      <div class="flex space-x-2 mb-4">
        <select v-model="reportFilter.status" @change="loadReports" class="border rounded px-3 py-1.5 text-sm">
          <option value="">全部状态</option>
          <option value="PUBLISHED">已发布</option>
          <option value="DRAFT">草稿</option>
        </select>
        <button @click="newReport" class="bg-primary text-white px-4 py-1.5 rounded text-sm hover:bg-primary-dark ml-auto">+ 发布新报道</button>
      </div>
      <table class="w-full text-sm">
        <thead class="bg-gray-50">
          <tr>
            <th class="text-left px-4 py-2">ID</th>
            <th class="text-left px-4 py-2">标题</th>
            <th class="text-left px-4 py-2">版本</th>
            <th class="text-left px-4 py-2">状态</th>
            <th class="text-left px-4 py-2">创建时间</th>
            <th class="text-left px-4 py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in reports" :key="r.id" class="border-b">
            <td class="px-4 py-2">{{ r.id }}</td>
            <td class="px-4 py-2 max-w-xs truncate">{{ r.title }}</td>
            <td class="px-4 py-2">{{ r.newVersion }}</td>
            <td class="px-4 py-2">
              <span :class="r.status === 'PUBLISHED' ? 'text-green-600' : 'text-gray-400'">{{ r.status }}</span>
            </td>
            <td class="px-4 py-2">{{ formatDate(r.createdAt) }}</td>
            <td class="px-4 py-2 space-x-2">
              <button @click="editReport(r)" class="text-primary hover:underline">编辑</button>
              <button @click="deleteReport(r.id)" class="text-red-500 hover:underline">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 知识库管理 -->
    <div v-if="activeTab === 'knowledge'">
      <div class="flex space-x-2 mb-4">
        <button @click="newKnowledge" class="bg-primary text-white px-4 py-1.5 rounded text-sm hover:bg-primary-dark ml-auto">+ 发布新文档</button>
      </div>
      <table class="w-full text-sm">
        <thead class="bg-gray-50">
          <tr>
            <th class="text-left px-4 py-2">ID</th>
            <th class="text-left px-4 py-2">标题</th>
            <th class="text-left px-4 py-2">来源</th>
            <th class="text-left px-4 py-2">状态</th>
            <th class="text-left px-4 py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in knowledgeDocs" :key="d.id" class="border-b">
            <td class="px-4 py-2">{{ d.id }}</td>
            <td class="px-4 py-2 max-w-xs truncate">{{ d.title }}</td>
            <td class="px-4 py-2">{{ d.sourceType }}</td>
            <td class="px-4 py-2">{{ d.status }}</td>
            <td class="px-4 py-2 space-x-2">
              <button @click="editKnowledge(d)" class="text-primary hover:underline">编辑</button>
              <button @click="deleteKnowledge(d.id)" class="text-red-500 hover:underline">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 追踪技术管理 -->
    <div v-if="activeTab === 'techs'">
      <!-- 频率控制区域 -->
      <div class="bg-blue-50 border border-blue-200 rounded-lg p-4 mb-4 flex items-center space-x-4">
        <span class="text-sm font-medium text-blue-700">追踪频率：</span>
        <select v-model.number="trackerFrequency" @change="updateFrequency"
          class="border border-blue-300 rounded px-3 py-1.5 text-sm bg-white">
          <option :value="30">30分钟</option>
          <option :value="60">1小时</option>
          <option :value="180">3小时</option>
          <option :value="360">6小时</option>
          <option :value="720">12小时</option>
          <option :value="1440">24小时</option>
        </select>
        <button @click="checkNow" :disabled="checkingNow"
          class="bg-blue-600 text-white px-4 py-1.5 rounded text-sm hover:bg-blue-700 disabled:opacity-50 transition">
          {{ checkingNow ? '检查中...' : '立即检查' }}
        </button>
      </div>
      <div class="flex space-x-2 mb-4">
        <button @click="newTech" class="bg-primary text-white px-4 py-1.5 rounded text-sm hover:bg-primary-dark">+ 添加技术</button>
      </div>
      <table class="w-full text-sm">
        <thead class="bg-gray-50">
          <tr>
            <th class="text-left px-4 py-2">技术名称</th>
            <th class="text-left px-4 py-2">分类</th>
            <th class="text-left px-4 py-2">GitHub仓库</th>
            <th class="text-left px-4 py-2">官网</th>
            <th class="text-left px-4 py-2">RSS源</th>
            <th class="text-left px-4 py-2">追踪模式</th>
            <th class="text-left px-4 py-2">状态</th>
            <th class="text-left px-4 py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in trackedTechs" :key="t.id" class="border-b">
            <td class="px-4 py-2">{{ t.name }}</td>
            <td class="px-4 py-2">{{ t.category }}</td>
            <td class="px-4 py-2 text-xs">{{ t.githubRepo }}</td>
            <td class="px-4 py-2 text-xs truncate">{{ t.officialUrl }}</td>
            <td class="px-4 py-2 text-xs truncate">{{ t.rssUrl }}</td>
            <td class="px-4 py-2">
              <span :class="{
                'text-green-600': t.trackingMode === 'RELEASE' || !t.trackingMode,
                'text-blue-600': t.trackingMode === 'TAG',
                'text-purple-600': t.trackingMode === 'COMMIT'
              }" class="text-xs font-medium">{{ t.trackingMode || 'RELEASE' }}</span>
            </td>
            <td class="px-4 py-2">
              <select :value="t.status" @change="updateTechStatus(t.id, ($event.target as HTMLSelectElement).value)"
                class="border rounded px-2 py-0.5 text-xs">
                <option value="ACTIVE">ACTIVE</option>
                <option value="PAUSED">PAUSED</option>
              </select>
            </td>
            <td class="px-4 py-2 space-x-2 text-sm">
              <button @click="editTech(t)" class="text-primary hover:underline">编辑</button>
              <button @click="deleteTech(t.id)" class="text-red-500 hover:underline">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分类管理 -->
    <div v-if="activeTab === 'categories'">
      <div class="flex space-x-2 mb-4">
        <input v-model="newCategory.name" placeholder="分类名称" class="border rounded px-3 py-1.5 text-sm" />
        <input v-model="newCategory.description" placeholder="描述" class="border rounded px-3 py-1.5 text-sm" />
        <input v-model.number="newCategory.sortOrder" type="number" placeholder="排序" class="border rounded px-3 py-1.5 text-sm w-20" />
        <button @click="addCategory" class="bg-primary text-white px-4 py-1.5 rounded text-sm">添加</button>
      </div>
      <div class="space-y-2">
        <div v-for="cat in categories" :key="cat.id" class="flex items-center space-x-3 bg-white p-3 rounded border">
          <span class="font-medium">{{ cat.name }}</span>
          <span class="text-sm text-gray-400">{{ cat.description }}</span>
          <span class="text-xs text-gray-300">排序: {{ cat.sortOrder }}</span>
          <button @click="deleteCategory(cat.id)" class="ml-auto text-red-500 text-sm hover:underline">删除</button>
        </div>
      </div>
    </div>

    <!-- 勘误审核 -->
    <div v-if="activeTab === 'errata'">
      <div class="flex space-x-2 mb-4">
        <select v-model="errataFilter.status" @change="loadErrata" class="border rounded px-3 py-1.5 text-sm">
          <option value="">全部状态</option>
          <option value="PENDING">待审核</option>
          <option value="ACCEPTED">已采纳</option>
          <option value="REJECTED">已驳回</option>
        </select>
      </div>
      <table class="w-full text-sm">
        <thead class="bg-gray-50">
          <tr>
            <th class="text-left px-4 py-2">ID</th>
            <th class="text-left px-4 py-2">文档</th>
            <th class="text-left px-4 py-2">提交者</th>
            <th class="text-left px-4 py-2">内容</th>
            <th class="text-left px-4 py-2">状态</th>
            <th class="text-left px-4 py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in errataList" :key="e.id" class="border-b">
            <td class="px-4 py-2">{{ e.id }}</td>
            <td class="px-4 py-2 max-w-xs truncate">{{ e.docTitle }}</td>
            <td class="px-4 py-2">{{ e.userNickname }}</td>
            <td class="px-4 py-2 max-w-sm truncate">{{ e.content }}</td>
            <td class="px-4 py-2">
              <span :class="errataStatusColor(e.status)">{{ e.status }}</span>
            </td>
            <td class="px-4 py-2 space-x-1" v-if="e.status === 'PENDING'">
              <button @click="reviewErrata(e.id, 'ACCEPTED')" class="text-green-600 hover:underline text-xs">采纳</button>
              <button @click="reviewErrata(e.id, 'REJECTED')" class="text-red-500 hover:underline text-xs">驳回</button>
            </td>
            <td v-else class="px-4 py-2 text-xs text-gray-400">{{ e.adminNote || '-' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 用户管理 -->
    <div v-if="activeTab === 'users'">
      <table class="w-full text-sm">
        <thead class="bg-gray-50">
          <tr>
            <th class="text-left px-4 py-2">ID</th>
            <th class="text-left px-4 py-2">邮箱</th>
            <th class="text-left px-4 py-2">昵称</th>
            <th class="text-left px-4 py-2">角色</th>
            <th class="text-left px-4 py-2">状态</th>
            <th class="text-left px-4 py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id" class="border-b">
            <td class="px-4 py-2">{{ u.id }}</td>
            <td class="px-4 py-2">{{ u.email }}</td>
            <td class="px-4 py-2">{{ u.nickname }}</td>
            <td class="px-4 py-2">
              <select :value="u.role" @change="updateUserRole(u.id, ($event.target as HTMLSelectElement).value)"
                class="border rounded px-2 py-0.5 text-xs">
                <option value="USER">USER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </td>
            <td class="px-4 py-2">
              <select :value="u.status" @change="updateUserStatus(u.id, ($event.target as HTMLSelectElement).value)"
                class="border rounded px-2 py-0.5 text-xs">
                <option value="ACTIVE">ACTIVE</option>
                <option value="DISABLED">DISABLED</option>
              </select>
            </td>
            <td class="px-4 py-2 text-sm space-x-1">
              <button @click="viewUserSessions(u.id, u.nickname)" class="text-primary hover:underline">会话</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="editModal.show" class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center" @click.self="editModal.show = false">
      <div class="bg-white rounded-xl p-6 w-full max-w-2xl max-h-[80vh] overflow-y-auto">
        <h3 class="text-lg font-semibold mb-4">{{ editModal.title }}</h3>
        <div class="space-y-3">
          <!-- 报道和知识文档字段 -->
          <div v-if="editModal.type !== 'tech'">
            <div>
              <label class="text-sm text-gray-600">标题</label>
              <input v-model="editModal.data.title" class="w-full border rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label class="text-sm text-gray-600">内容</label>
              <textarea v-model="editModal.data.content" rows="10" class="w-full border rounded px-3 py-2 text-sm font-mono"></textarea>
            </div>
            <div class="flex space-x-3">
              <div>
                <label class="text-sm text-gray-600">分类</label>
                <select v-model="editModal.data.categoryId" class="border rounded px-3 py-2 text-sm">
                  <option :value="null">无分类</option>
                  <option v-for="cat in categories" :key="cat.id" :value="cat.id">{{ cat.name }}</option>
                </select>
              </div>
              <div v-if="editModal.type === 'report'">
                <label class="text-sm text-gray-600">状态</label>
                <select v-model="editModal.data.status" class="border rounded px-3 py-2 text-sm">
                  <option value="DRAFT">草稿</option>
                  <option value="PUBLISHED">已发布</option>
                </select>
              </div>
            </div>
            <!-- 报道专属：关联技术 + 技术指数 -->
            <div v-if="editModal.type === 'report'" class="flex space-x-3 mt-3">
              <div class="flex-1">
                <label class="text-sm text-gray-600">关联技术</label>
                <select v-model="editModal.data.trackedTechId" class="w-full border rounded px-3 py-2 text-sm">
                  <option :value="null">无关联</option>
                  <option v-for="t in trackedTechs" :key="t.id" :value="t.id">{{ t.name }}</option>
                </select>
              </div>
              <div>
                <label class="text-sm text-gray-600">技术指数 (0-1000)</label>
                <input v-model.number="editModal.data.techIndex" type="number" min="0" max="1000"
                  class="w-full border rounded px-3 py-2 text-sm" placeholder="500" />
              </div>
            </div>
          </div>

          <!-- 追踪技术字段 -->
          <div v-if="editModal.type === 'tech'" class="space-y-3">
            <div>
              <label class="text-sm text-gray-600">技术名称</label>
              <input v-model="editModal.data.title" class="w-full border rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label class="text-sm text-gray-600">分类</label>
              <input v-model="editModal.data.category" class="w-full border rounded px-3 py-2 text-sm" placeholder="如：框架、语言、工具" />
            </div>
            <div>
              <label class="text-sm text-gray-600">GitHub 仓库</label>
              <input v-model="editModal.data.githubRepo" class="w-full border rounded px-3 py-2 text-sm" placeholder="owner/repo" />
            </div>
            <div>
              <label class="text-sm text-gray-600">官方网址</label>
              <input v-model="editModal.data.officialUrl" type="url" class="w-full border rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label class="text-sm text-gray-600">RSS 源</label>
              <input v-model="editModal.data.rssUrl" type="url" class="w-full border rounded px-3 py-2 text-sm" />
            </div>
            <div>
              <label class="text-sm text-gray-600">追踪模式</label>
              <select v-model="editModal.data.trackingMode" class="w-full border rounded px-3 py-2 text-sm">
                <option value="RELEASE">RELEASE (GitHub Release)</option>
                <option value="TAG">TAG (Git 标签)</option>
                <option value="COMMIT">COMMIT (最新提交)</option>
              </select>
            </div>
            <div>
              <label class="text-sm text-gray-600">状态</label>
              <select v-model="editModal.data.status" class="border rounded px-3 py-2 text-sm">
                <option value="ACTIVE">ACTIVE</option>
                <option value="PAUSED">PAUSED</option>
              </select>
            </div>
          </div>
        </div>
        <div class="flex justify-end space-x-2 mt-4">
          <button @click="editModal.show = false" class="px-4 py-1.5 rounded text-sm bg-gray-100 hover:bg-gray-200">取消</button>
          <button @click="saveEdit" class="px-4 py-1.5 rounded text-sm bg-primary text-white hover:bg-primary-dark">保存</button>
        </div>
      </div>
    </div>

    <!-- 用户会话弹窗 -->
    <div v-if="sessionModal.show" class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center" @click.self="sessionModal.show = false">
      <div class="bg-white rounded-xl p-6 w-full max-w-4xl max-h-[80vh] overflow-y-auto">
        <h3 class="text-lg font-semibold mb-4">用户 {{ sessionModal.userName }} 的AI会话</h3>
        <div v-if="sessionModal.sessions.length === 0" class="text-center text-gray-400 py-8">
          此用户暂无会话
        </div>
        <div v-else class="space-y-3">
          <div v-for="session in sessionModal.sessions" :key="session.id" class="border rounded p-4 bg-gray-50">
            <div class="flex justify-between items-start">
              <div class="flex-1">
                <h4 class="font-medium">{{ session.title }}</h4>
                <p class="text-xs text-gray-400 mt-1">ID: {{ session.id }}</p>
                <p class="text-xs text-gray-400">创建时间: {{ formatDate(session.createdAt) }}</p>
                <p class="text-xs text-gray-400">更新时间: {{ formatDate(session.updatedAt) }}</p>
              </div>
              <button @click="deleteUserSession(session.id)" class="text-red-500 hover:underline text-sm">删除</button>
            </div>
          </div>
        </div>
        <div class="flex justify-end mt-4">
          <button @click="sessionModal.show = false" class="px-4 py-1.5 rounded text-sm bg-gray-100 hover:bg-gray-200">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import api from '../services/api'

const tabs = [
  { key: 'reports', label: '报道管理' },
  { key: 'knowledge', label: '知识库管理' },
  { key: 'techs', label: '追踪技术' },
  { key: 'categories', label: '分类管理' },
  { key: 'errata', label: '勘误审核' },
  { key: 'users', label: '用户管理' }
]
const activeTab = ref('reports')

const reports = ref<any[]>([])
const knowledgeDocs = ref<any[]>([])
const trackedTechs = ref<any[]>([])
const categories = ref<any[]>([])
const errataList = ref<any[]>([])
const users = ref<any[]>([])

const reportFilter = reactive({ status: '' })
const errataFilter = reactive({ status: '' })
const newCategory = reactive({ name: '', description: '', sortOrder: 0 })
const trackerFrequency = ref(360)
const checkingNow = ref(false)

const editModal = reactive({
  show: false,
  title: '',
  type: '' as 'report' | 'knowledge' | 'tech',
  id: 0,
  data: {} as any
})

const sessionModal = reactive({
  show: false,
  userId: 0,
  userName: '',
  sessions: [] as any[]
})

watch(activeTab, (tab) => {
  if (tab === 'reports') loadReports()
  else if (tab === 'knowledge') loadKnowledge()
  else if (tab === 'techs') loadTrackedTechs()
  else if (tab === 'categories') loadCategories()
  else if (tab === 'errata') loadErrata()
  else if (tab === 'users') loadUsers()
})

onMounted(() => {
  loadReports()
  loadCategories()
})

async function loadReports() {
  try {
    const params: any = { page: 1, size: 50 }
    if (reportFilter.status) params.status = reportFilter.status
    const res: any = await api.get('/admin/reports', { params })
    reports.value = res.data?.records || []
  } catch { /* ignore */ }
}

async function loadKnowledge() {
  try {
    const res: any = await api.get('/admin/knowledge', { params: { page: 1, size: 50 } })
    knowledgeDocs.value = res.data?.records || []
  } catch { /* ignore */ }
}

async function loadTrackedTechs() {
  try {
    const res: any = await api.get('/admin/techs')
    trackedTechs.value = res.data || []
  } catch { /* ignore */ }
  // 同时加载频率
  try {
    const res: any = await api.get('/admin/tracker/frequency')
    trackerFrequency.value = res.data?.minutes || 360
  } catch { /* ignore */ }
}

async function updateFrequency() {
  try {
    await api.put('/admin/tracker/frequency', { minutes: trackerFrequency.value })
  } catch { /* ignore */ }
}

async function checkNow() {
  checkingNow.value = true
  try {
    await api.post('/admin/tracker/check-now')
  } catch { /* ignore */ }
  finally {
    setTimeout(() => { checkingNow.value = false }, 3000)
  }
}

async function loadCategories() {
  try {
    const res: any = await api.get('/categories')
    categories.value = res.data || []
  } catch { /* ignore */ }
}

async function loadErrata() {
  try {
    const params: any = { page: 1, size: 50 }
    if (errataFilter.status) params.status = errataFilter.status
    const res: any = await api.get('/admin/errata', { params })
    errataList.value = res.data?.records || []
  } catch { /* ignore */ }
}

async function loadUsers() {
  try {
    const res: any = await api.get('/admin/users', { params: { page: 1, size: 50 } })
    users.value = res.data?.records || []
  } catch { /* ignore */ }
}

function editReport(r: any) {
  editModal.show = true
  editModal.title = '编辑报道'
  editModal.type = 'report'
  editModal.id = r.id
  editModal.data = { title: r.title, content: r.content, categoryId: r.categoryId, trackedTechId: r.trackedTechId, techIndex: r.techIndex, status: r.status }
  loadTrackedTechs()
}

function newReport() {
  editModal.show = true
  editModal.title = '发布新报道'
  editModal.type = 'report'
  editModal.id = 0
  editModal.data = { title: '', content: '', categoryId: null, trackedTechId: null, techIndex: 500, status: 'DRAFT' }
  loadTrackedTechs()
}

function editKnowledge(d: any) {
  editModal.show = true
  editModal.title = '编辑知识文档'
  editModal.type = 'knowledge'
  editModal.id = d.id
  editModal.data = { title: d.title, content: d.content, categoryId: d.categoryId, status: d.status }
}

function newKnowledge() {
  editModal.show = true
  editModal.title = '发布新文档'
  editModal.type = 'knowledge'
  editModal.id = 0
  editModal.data = { title: '', content: '', categoryId: null, status: 'ACTIVE' }
}

async function saveEdit() {
  try {
    if (editModal.type === 'report') {
      if (editModal.id === 0) {
        // 新建报道
        await api.post('/admin/reports', {
          ...editModal.data,
          trackedTechId: editModal.data.trackedTechId || null,
          techIndex: editModal.data.techIndex ?? 500
        })
      } else {
        // 编辑报道
        await api.put(`/admin/reports/${editModal.id}`, {
          ...editModal.data,
          trackedTechId: editModal.data.trackedTechId || null,
          techIndex: editModal.data.techIndex ?? 500
        })
      }
      await loadReports()
    } else if (editModal.type === 'knowledge') {
      if (editModal.id === 0) {
        // 新建知识文档
        await api.post('/admin/knowledge', editModal.data)
      } else {
        // 编辑知识文档
        await api.put(`/admin/knowledge/${editModal.id}`, editModal.data)
      }
      await loadKnowledge()
    } else if (editModal.type === 'tech') {
      const techData = {
        name: editModal.data.title,
        category: editModal.data.category,
        githubRepo: editModal.data.githubRepo,
        officialUrl: editModal.data.officialUrl,
        rssUrl: editModal.data.rssUrl,
        trackingMode: editModal.data.trackingMode,
        status: editModal.data.status
      }
      if (editModal.id === 0) {
        // 新建技术
        await api.post('/admin/techs', techData)
      } else {
        // 编辑技术
        await api.put(`/admin/techs/${editModal.id}`, techData)
      }
      await loadTrackedTechs()
    }
    editModal.show = false
  } catch { /* ignore */ }
}

async function deleteReport(id: number) {
  if (!confirm('确认删除此报道？')) return
  await api.delete(`/admin/reports/${id}`)
  await loadReports()
}

async function deleteKnowledge(id: number) {
  if (!confirm('确认删除此文档？')) return
  await api.delete(`/admin/knowledge/${id}`)
  await loadKnowledge()
}

function editTech(t: any) {
  editModal.show = true
  editModal.title = '编辑追踪技术'
  editModal.type = 'tech'
  editModal.id = t.id
  editModal.data = {
    ...t,
    title: t.name
  }
}

function newTech() {
  editModal.show = true
  editModal.title = '添加追踪技术'
  editModal.type = 'tech'
  editModal.id = 0
  editModal.data = {
    title: '',
    category: '',
    githubRepo: '',
    officialUrl: '',
    rssUrl: '',
    trackingMode: 'RELEASE',
    status: 'ACTIVE'
  }
}

async function deleteTech(id: number) {
  if (!confirm('确认删除此技术？')) return
  await api.delete(`/admin/techs/${id}`)
  await loadTrackedTechs()
}

async function updateTechStatus(id: number, status: string) {
  await api.put(`/admin/techs/${id}`, { status })
  await loadTrackedTechs()
}

async function addCategory() {
  if (!newCategory.name.trim()) return
  await api.post('/categories', newCategory)
  newCategory.name = ''
  newCategory.description = ''
  newCategory.sortOrder = 0
  await loadCategories()
}

async function deleteCategory(id: number) {
  if (!confirm('确认删除此分类？')) return
  await api.delete(`/categories/${id}`)
  await loadCategories()
}

async function reviewErrata(id: number, status: string) {
  const adminNote = status === 'REJECTED' ? prompt('驳回原因（可选）：') || '' : ''
  await api.put(`/admin/errata/${id}`, null, { params: { status, adminNote } })
  await loadErrata()
}

async function updateUserRole(id: number, role: string) {
  await api.put(`/admin/users/${id}`, { role })
  await loadUsers()
}

async function updateUserStatus(id: number, status: string) {
  await api.put(`/admin/users/${id}`, { status })
  await loadUsers()
}

function errataStatusColor(s: string) {
  if (s === 'PENDING') return 'text-yellow-600'
  if (s === 'ACCEPTED') return 'text-green-600'
  return 'text-red-500'
}

function formatDate(date: string) {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}

async function viewUserSessions(userId: number, userName: string) {
  sessionModal.userId = userId
  sessionModal.userName = userName
  try {
    const res: any = await api.get(`/admin/users/${userId}/sessions`)
    sessionModal.sessions = res.data || []
    sessionModal.show = true
  } catch { /* ignore */ }
}

async function deleteUserSession(sessionId: number) {
  if (!confirm('确认删除此会话？')) return
  try {
    await api.delete(`/admin/users/${sessionModal.userId}/sessions/${sessionId}`)
    const res: any = await api.get(`/admin/users/${sessionModal.userId}/sessions`)
    sessionModal.sessions = res.data || []
  } catch { /* ignore */ }
}
</script>
