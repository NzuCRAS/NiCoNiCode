<template>
  <div class="flex h-[calc(100vh-3.5rem)]">
    <!-- Sidebar -->
    <aside class="w-64 bg-gray-50 border-r border-gray-200 flex flex-col">
      <div class="p-3">
        <button @click="newChat" class="w-full bg-primary text-white py-2 rounded-lg hover:bg-primary-dark transition text-sm">
          + 新对话
        </button>
      </div>
      <div class="flex-1 overflow-y-auto">
        <div v-for="session in chatStore.sessions" :key="session.id"
          @click="selectSession(session.id)"
          :class="['px-3 py-2.5 cursor-pointer text-sm border-b border-gray-100 flex justify-between items-center group',
            chatStore.currentSessionId === session.id ? 'bg-indigo-50 text-primary' : 'hover:bg-gray-100 text-gray-700']">
          <span class="truncate flex-1">{{ session.title || '新对话' }}</span>
          <button @click.stop="deleteSession(session.id)"
            class="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500 ml-2">×</button>
        </div>
      </div>
    </aside>

    <!-- Chat Area -->
    <div class="flex-1 flex flex-col">
      <!-- Messages -->
      <div ref="messagesContainer" class="flex-1 overflow-y-auto p-4 space-y-4">
        <div v-if="!chatStore.messages.length" class="flex items-center justify-center h-full text-gray-400">
          <div class="text-center">
            <p class="text-lg mb-2">有什么技术问题？尽管问我！</p>
            <p class="text-sm">支持编程问答、GitHub 分析、技术方案设计</p>
          </div>
        </div>
        <div v-for="(msg, i) in chatStore.messages" :key="i"
          v-show="!(msg.role === 'ASSISTANT' && msg.content === '')"
          :class="['flex group', msg.role === 'USER' ? 'justify-end' : 'justify-start']">
          <div class="relative max-w-[75%]">
            <!-- 编辑模式 -->
            <div v-if="editingMessageId === msg.id && msg.id > 0" class="bg-white border border-primary rounded-2xl px-4 py-3">
              <textarea v-model="editContent" rows="3"
                class="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm resize-none focus:outline-none focus:border-primary"></textarea>
              <div class="flex justify-end space-x-2 mt-2">
                <button @click="cancelEdit" class="text-sm text-gray-500 hover:text-gray-700 px-3 py-1">取消</button>
                <button @click="confirmEdit(msg.id)" class="text-sm bg-primary text-white px-3 py-1 rounded-lg hover:bg-primary-dark">确认</button>
              </div>
            </div>
            <!-- 正常消息 -->
            <div v-else :class="[
              'rounded-2xl px-4 py-3 text-sm',
              msg.role === 'USER'
                ? 'bg-primary text-white rounded-br-md'
                : 'bg-white border border-gray-200 rounded-bl-md'
            ]">
              <!-- 思考链面板 -->
              <details v-if="msg.role === 'ASSISTANT' && msg.thinking" class="thinking-panel mb-2">
                <summary class="text-xs text-gray-400 cursor-pointer select-none hover:text-gray-600">
                  💭 思考过程 · {{ strategyLabel(msg.thinking.strategy) }}
                </summary>
                <div class="text-xs text-gray-500 mt-1 space-y-1 pl-2 border-l-2 border-indigo-200">
                  <div>🎯 意图: {{ intentLabel(msg.thinking.intent) }} / {{ msg.thinking.subIntent }} ({{ Math.round(msg.thinking.confidence * 100) }}%)</div>
                  <div v-if="msg.thinking.rewrittenQuery">🔄 重写: {{ msg.thinking.rewrittenQuery }}</div>
                  <div v-if="msg.thinking.tools.length">🔧 工具: {{ msg.thinking.tools.map(t => t.label).join('、') }}</div>
                  <div v-if="msg.thinking.ragDocs.length">📚 检索文档:
                    <div v-for="doc in msg.thinking.ragDocs" :key="doc.id" class="ml-2">
                      <router-link :to="`/knowledge/${doc.id}`" class="text-indigo-500 hover:underline">
                        {{ doc.title }}
                      </router-link>
                      <span class="text-gray-400 ml-1">({{ Math.round(doc.score * 100) }}%)</span>
                    </div>
                  </div>
                </div>
              </details>
              <div v-if="msg.role === 'ASSISTANT'" class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
              <div v-else class="whitespace-pre-wrap">{{ msg.content }}</div>
            </div>
            <!-- 操作按钮 -->
            <div v-if="editingMessageId !== msg.id"
              :class="['absolute flex space-x-1 whitespace-nowrap opacity-0 group-hover:opacity-100 transition-opacity',
                msg.role === 'USER' ? 'right-0 -bottom-7' : 'left-0 -bottom-7']">
              <!-- 复制 -->
              <button @click="copyMessage(msg.content)" class="text-xs text-gray-400 hover:text-gray-600 px-1.5 py-0.5 rounded hover:bg-gray-100"
                :title="'复制'">
                <span v-if="copiedMessageIndex === i" class="text-green-500">已复制</span>
                <span v-else>复制</span>
              </button>
              <!-- AI 消息操作 -->
              <template v-if="msg.role === 'ASSISTANT'">
                <button v-if="isLastAssistantMessage(i)" @click="regenerate"
                  class="text-xs text-gray-400 hover:text-gray-600 px-1.5 py-0.5 rounded hover:bg-gray-100">重新生成</button>
              </template>
              <!-- 用户消息操作 -->
              <template v-if="msg.role === 'USER'">
                <button v-if="isLastUserMessage(i) && msg.id > 0" @click="startEdit(msg)"
                  class="text-xs text-gray-400 hover:text-gray-600 px-1.5 py-0.5 rounded hover:bg-gray-100">编辑</button>
                <button v-if="msg.id > 0" @click="deleteMsg(msg.id)"
                  class="text-xs text-gray-400 hover:text-red-500 px-1.5 py-0.5 rounded hover:bg-gray-100">删除</button>
              </template>
            </div>
          </div>
        </div>
        <!-- 流式加载中 / 停止生成（只在无流式内容时显示） -->
        <div v-if="chatStore.isCurrentSessionLoading && !isStreaming" class="flex justify-start">
          <div class="bg-white border border-gray-200 rounded-2xl rounded-bl-md px-4 py-3 text-sm text-gray-400 flex items-center space-x-2">
            <span v-if="chatStore.toolsInUse.length">
              🔍 {{ formatToolNames(chatStore.toolsInUse) }}
            </span>
            <span v-else>思考中...</span>
            <button @click="stopGeneration" class="text-xs text-red-400 hover:text-red-600 border border-red-300 px-2 py-0.5 rounded-full hover:bg-red-50 ml-2">
              停止生成
            </button>
          </div>
        </div>
        <!-- 流式生成中的停止按钮（内容已在气泡中显示，仅显示按钮） -->
        <div v-if="chatStore.isCurrentSessionLoading && isStreaming" class="flex justify-center mt-1">
          <button @click="stopGeneration" class="text-xs text-red-400 hover:text-red-600 border border-red-300 px-3 py-1 rounded-full hover:bg-red-50">
            停止生成
          </button>
        </div>
      </div>

      <!-- Input -->
      <div class="border-t border-gray-200 bg-white p-4">
        <div class="max-w-4xl mx-auto flex space-x-3">
          <textarea v-model="input" @keydown.enter.prevent="handleSend" rows="1"
            placeholder="输入你的问题... (Enter 发送)"
            class="flex-1 border border-gray-300 rounded-xl px-4 py-2.5 resize-none focus:outline-none focus:border-primary text-sm"></textarea>
          <button @click="handleSend" :disabled="chatStore.isCurrentSessionLoading || !input.trim()"
            class="bg-primary text-white px-5 py-2.5 rounded-xl hover:bg-primary-dark disabled:opacity-50 transition text-sm">
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { marked } from 'marked'

// 配置 marked: GFM + 换行支持
marked.setOptions({ breaks: true, gfm: true })

const chatStore = useChatStore()
const route = useRoute()
const router = useRouter()
const input = ref('')
const messagesContainer = ref<HTMLElement>()
const editingMessageId = ref<number | null>(null)
const editContent = ref('')
const copiedMessageIndex = ref<number | null>(null)

const isStreaming = computed(() => {
  return chatStore.streamingStarted && chatStore.isCurrentSessionLoading
})

onMounted(async () => {
  // 注册全局路由跳转方法，供 v-html 内的 onclick 使用
  ;(window as any).__routerPush = (path: string) => router.push(path)
  await chatStore.loadSessions()
  if (route.params.id) {
    await chatStore.loadMessages(Number(route.params.id))
  }
})

watch(() => chatStore.messages.length, () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
})

// 也监听最后一条消息内容变化（流式时自动滚动）
watch(() => {
  const msgs = chatStore.messages
  return msgs.length > 0 ? msgs[msgs.length - 1].content.length : 0
}, () => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  })
})

async function handleSend() {
  if (!input.value.trim() || chatStore.isCurrentSessionLoading) return
  const msg = input.value.trim()
  input.value = ''
  await chatStore.sendMessageStream(msg, () => {})
}

async function newChat() {
  const existingNewChat = chatStore.sessions.find(s => {
    return s.title === "新对话" || !chatStore.messages.length
  })

  if (existingNewChat) {
    await selectSession(existingNewChat.id)
  } else {
    await chatStore.createSession()
  }
}

async function selectSession(id: number) {
  await chatStore.loadMessages(id)
}

async function deleteSession(id: number) {
  await chatStore.deleteSession(id)
}

function isLastAssistantMessage(index: number): boolean {
  const msgs = chatStore.messages
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'ASSISTANT') return i === index
  }
  return false
}

function isLastUserMessage(index: number): boolean {
  const msgs = chatStore.messages
  for (let i = msgs.length - 1; i >= 0; i--) {
    if (msgs[i].role === 'USER') return i === index
  }
  return false
}

async function copyMessage(content: string) {
  try {
    await navigator.clipboard.writeText(content)
    const idx = chatStore.messages.findIndex(m => m.content === content)
    copiedMessageIndex.value = idx
    setTimeout(() => { copiedMessageIndex.value = null }, 1500)
  } catch { /* ignore */ }
}

function startEdit(msg: { id: number; content: string }) {
  editingMessageId.value = msg.id
  editContent.value = msg.content
}

function cancelEdit() {
  editingMessageId.value = null
  editContent.value = ''
}

async function confirmEdit(messageId: number) {
  if (!editContent.value.trim()) return
  const content = editContent.value.trim()
  editingMessageId.value = null
  editContent.value = ''
  await chatStore.editAndResend(messageId, content)
}

async function deleteMsg(messageId: number) {
  await chatStore.deleteMessage(messageId)
}

async function regenerate() {
  await chatStore.regenerateLastReply()
}

async function stopGeneration() {
  await chatStore.cancelStream()
}

function renderMarkdown(content: string): string {
  if (!content) return ''
  try {
    let html = marked.parse(content) as string
    // [报道#42] → 可点击链接
    html = html.replace(/\[报道#(\d+)\]/g,
      '<a href="/reports/$1" class="source-link" onclick="event.preventDefault();window.__routerPush(\'/reports/$1\')">📄报道原文</a>')
    // [文档#15] → 知识库链接
    html = html.replace(/\[文档#(\d+)\]/g,
      '<a href="/knowledge/$1" class="source-link" onclick="event.preventDefault();window.__routerPush(\'/knowledge/$1\')">📄文档原文</a>')
    return html
  } catch {
    return content
  }
}

function intentLabel(intent: string): string {
  const map: Record<string, string> = {
    VERSION_UPDATE: '版本更新',
    REPORT_QUERY: '报道查询',
    TECH_QUERY: '技术咨询',
    CODE_HELP: '代码帮助',
    COMPARISON: '技术对比',
    GITHUB_ANALYSIS: 'GitHub分析',
    GENERAL_CHAT: '闲聊',
    UNCLEAR: '不明确',
  }
  return map[intent] || intent
}

function strategyLabel(strategy: string): string {
  const map: Record<string, string> = {
    DIRECT_ANSWER: '直接回答',
    RAG_ONLY: '知识检索',
    WORKFLOW: '工作流',
  }
  return map[strategy] || strategy
}

/** 将驼峰工具名转换为可读文字，并去重展示
 *  例: ["getRecentReportsForTech", "searchReports"] → "查询近期报道、搜索报道"
 */
const TOOL_LABEL_MAP: Record<string, string> = {
  getRecentReportsForTech: '查询近期报道',
  getReportsByDate: '按日期查询报道',
  searchReports: '搜索报道',
  getTechInfo: '查询技术信息',
  listTrackedTechnologies: '列出追踪技术',
  recordTechMention: '记录技术提及',
  knowledgeSearch: '搜索知识库',
}

function formatToolNames(tools: string[]): string {
  const labels = tools.map(t => TOOL_LABEL_MAP[t] || t)
  return labels.join('、') + '...'
}
</script>

<style scoped>
.thinking-panel summary {
  list-style: none;
}
.thinking-panel summary::-webkit-details-marker {
  display: none;
}
.thinking-panel summary::before {
  content: '▶ ';
  font-size: 0.6rem;
  transition: transform 0.15s;
  display: inline-block;
}
.thinking-panel[open] summary::before {
  transform: rotate(90deg);
}
:deep(.source-link) {
  display: inline-flex;
  align-items: center;
  font-size: 0.75rem;
  color: #4f46e5;
  background: #eef2ff;
  padding: 1px 6px;
  border-radius: 4px;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
}
:deep(.source-link:hover) {
  background: #c7d2fe;
}
</style>
