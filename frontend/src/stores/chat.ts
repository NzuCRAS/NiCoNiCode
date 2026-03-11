import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../services/api'

interface Session {
  id: number
  title: string
  updatedAt: string
}

interface Message {
  id: number
  role: string
  content: string
  createdAt: string
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<Message[]>([])
  const loading = ref(false)

  async function loadSessions() {
    const res: any = await api.get('/chat/sessions')
    sessions.value = res.data || []
  }

  async function createSession() {
    const res: any = await api.post('/chat/sessions')
    sessions.value.unshift(res.data)
    currentSessionId.value = res.data.id
    messages.value = []
    return res.data
  }

  async function loadMessages(sessionId: number) {
    currentSessionId.value = sessionId
    const res: any = await api.get(`/chat/sessions/${sessionId}/messages`)
    messages.value = res.data || []
  }

  async function sendMessage(message: string) {
    loading.value = true
    try {
      const res: any = await api.post('/chat/send', {
        sessionId: currentSessionId.value,
        message
      })
      currentSessionId.value = res.data.sessionId
      messages.value.push(
        { id: 0, role: 'USER', content: message, createdAt: new Date().toISOString() },
        { id: 0, role: 'ASSISTANT', content: res.data.reply, createdAt: new Date().toISOString() }
      )
      // 更新会话列表标题
      const session = sessions.value.find(s => s.id === res.data.sessionId)
      if (session) {
        session.title = res.data.sessionTitle
      } else {
        sessions.value.unshift({ id: res.data.sessionId, title: res.data.sessionTitle, updatedAt: new Date().toISOString() })
      }
      return res.data
    } finally {
      loading.value = false
    }
  }

  async function sendMessageStream(message: string, onChunk: (text: string) => void) {
    loading.value = true
    messages.value.push({ id: 0, role: 'USER', content: message, createdAt: new Date().toISOString() })
    messages.value.push({ id: 0, role: 'ASSISTANT', content: '', createdAt: new Date().toISOString() })

    const token = localStorage.getItem('token')
    try {
      const response = await fetch('/api/chat/send/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`
        },
        body: JSON.stringify({ sessionId: currentSessionId.value, message })
      })

      const reader = response.body!.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.substring(5).trim()
            if (line.includes('event:done') || line.includes('"sessionId"')) {
              try {
                const json = JSON.parse(data)
                currentSessionId.value = json.sessionId
              } catch { /* ignore */ }
            } else {
              const lastMsg = messages.value[messages.value.length - 1]
              lastMsg.content += data
              onChunk(data)
            }
          } else if (line.startsWith('event:message')) {
            // next data line is content
          } else if (line.startsWith('event:done')) {
            // handled above
          }
        }
      }
    } finally {
      loading.value = false
    }
  }

  async function deleteSession(sessionId: number) {
    await api.delete(`/chat/sessions/${sessionId}`)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
      messages.value = []
    }
  }

  return {
    sessions, currentSessionId, messages, loading,
    loadSessions, createSession, loadMessages, sendMessage, sendMessageStream, deleteSession
  }
})
