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
          :class="['flex', msg.role === 'USER' ? 'justify-end' : 'justify-start']">
          <div :class="[
            'max-w-[75%] rounded-2xl px-4 py-3 text-sm',
            msg.role === 'USER'
              ? 'bg-primary text-white rounded-br-md'
              : 'bg-white border border-gray-200 rounded-bl-md'
          ]">
            <div v-if="msg.role === 'ASSISTANT'" class="markdown-body" v-html="renderMarkdown(msg.content)"></div>
            <div v-else class="whitespace-pre-wrap">{{ msg.content }}</div>
          </div>
        </div>
        <div v-if="chatStore.loading" class="flex justify-start">
          <div class="bg-white border border-gray-200 rounded-2xl rounded-bl-md px-4 py-3 text-sm text-gray-400">
            思考中...
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="border-t border-gray-200 bg-white p-4">
        <div class="max-w-4xl mx-auto flex space-x-3">
          <textarea v-model="input" @keydown.enter.prevent="handleSend" rows="1"
            placeholder="输入你的问题... (Enter 发送)"
            class="flex-1 border border-gray-300 rounded-xl px-4 py-2.5 resize-none focus:outline-none focus:border-primary text-sm"></textarea>
          <button @click="handleSend" :disabled="chatStore.loading || !input.trim()"
            class="bg-primary text-white px-5 py-2.5 rounded-xl hover:bg-primary-dark disabled:opacity-50 transition text-sm">
            发送
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useChatStore } from '../stores/chat'
import { marked } from 'marked'

const chatStore = useChatStore()
const route = useRoute()
const input = ref('')
const messagesContainer = ref<HTMLElement>()

onMounted(async () => {
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

async function handleSend() {
  if (!input.value.trim() || chatStore.loading) return
  const msg = input.value.trim()
  input.value = ''
  await chatStore.sendMessage(msg)
}

async function newChat() {
  // 检查是否已经有空的新对话
  const existingNewChat = chatStore.sessions.find(s => {
    // 检查是否是"新对话"或没有发送消息的对话
    return s.title === "新对话" || !chatStore.messages.length
  })

  if (existingNewChat) {
    // 如果已经有未发送消息的新对话，直接选中它
    await selectSession(existingNewChat.id)
  } else {
    // 否则创建新会话
    await chatStore.createSession()
  }
}

async function selectSession(id: number) {
  await chatStore.loadMessages(id)
}

async function deleteSession(id: number) {
  await chatStore.deleteSession(id)
}

function renderMarkdown(content: string): string {
  if (!content) return ''
  try {
    return marked.parse(content) as string
  } catch {
    return content
  }
}
</script>
