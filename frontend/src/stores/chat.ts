import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
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
  const sessionMessagesCache = ref<Map<number, Message[]>>(new Map())
  const loadingSessionId = ref<number | null>(null)
  const abortController = ref<AbortController | null>(null)
  const toolsInUse = ref<string[]>([])

  const messages = computed(() => {
    if (currentSessionId.value === null) return []
    return sessionMessagesCache.value.get(currentSessionId.value) || []
  })

  const isCurrentSessionLoading = computed(() => loadingSessionId.value === currentSessionId.value && loadingSessionId.value !== null)

  function getOrCreateSessionMessages(sessionId: number): Message[] {
    if (!sessionMessagesCache.value.has(sessionId)) {
      sessionMessagesCache.value.set(sessionId, [])
    }
    return sessionMessagesCache.value.get(sessionId)!
  }

  function setSessionMessages(sessionId: number, msgs: Message[]) {
    sessionMessagesCache.value.set(sessionId, msgs)
  }

  /** 从 SSE data 行提取文本内容（兼容 JSON 编码和纯文本） */
  function extractMessageText(data: string): string {
    try {
      const parsed = JSON.parse(data)
      if (parsed && typeof parsed.t === 'string') return parsed.t
    } catch { /* not JSON, use raw */ }
    return data
  }

  /** 共享 SSE 流读取逻辑 */
  async function readSSEStream(
    response: Response,
    assistantMsg: Message,
    sessionId: number
  ) {
    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim()
        } else if (line.startsWith('data:')) {
          const data = line.substring(5).trim()
          if (currentEvent === 'tool') {
            try {
              const toolData = JSON.parse(data)
              toolsInUse.value = toolData.tools || []
            } catch { /* ignore */ }
          } else if (currentEvent === 'done' || data.includes('"sessionId"')) {
            toolsInUse.value = []
            try {
              const json = JSON.parse(data)
              if (currentSessionId.value === sessionId) {
                currentSessionId.value = json.sessionId
              }
            } catch { /* ignore */ }
          } else if (currentEvent === 'error') {
            toolsInUse.value = []
          } else if (currentEvent === 'message') {
            toolsInUse.value = []
            assistantMsg.content += extractMessageText(data)
          }
          currentEvent = ''
        }
      }
    }
  }

  /** 创建 SSE fetch 请求 */
  function createSSEFetch(sessionId: number, message: string, controller: AbortController): Promise<Response> {
    const token = localStorage.getItem('token')
    return fetch('/api/chat/send/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({ sessionId, message }),
      signal: controller.signal
    })
  }

  async function loadSessions() {
    const res: any = await api.get('/chat/sessions')
    sessions.value = res.data || []
  }

  async function createSession() {
    const res: any = await api.post('/chat/sessions')
    sessions.value.unshift(res.data)
    currentSessionId.value = res.data.id
    setSessionMessages(res.data.id, [])
    return res.data
  }

  async function loadMessages(sessionId: number) {
    currentSessionId.value = sessionId
    const res: any = await api.get(`/chat/sessions/${sessionId}/messages`)
    setSessionMessages(sessionId, res.data || [])
  }

  async function sendMessage(message: string) {
    const sessionId = currentSessionId.value
    loadingSessionId.value = sessionId

    const sessionMsgs = getOrCreateSessionMessages(sessionId!)
    const userMsg: Message = { id: 0, role: 'USER', content: message, createdAt: new Date().toISOString() }
    sessionMsgs.push(userMsg)

    try {
      const res: any = await api.post('/chat/send', { sessionId, message })

      const targetSessionId = res.data.sessionId
      const targetMsgs = getOrCreateSessionMessages(targetSessionId)

      if (targetSessionId !== sessionId) {
        const idx = sessionMsgs.indexOf(userMsg)
        if (idx >= 0) sessionMsgs.splice(idx, 1)
        targetMsgs.push(userMsg)
      }

      targetMsgs.push({ id: 0, role: 'ASSISTANT', content: res.data.reply, createdAt: new Date().toISOString() })

      if (currentSessionId.value === sessionId) {
        currentSessionId.value = targetSessionId
      }

      const session = sessions.value.find(s => s.id === targetSessionId)
      if (session) {
        session.title = res.data.sessionTitle
      } else {
        sessions.value.unshift({ id: targetSessionId, title: res.data.sessionTitle, updatedAt: new Date().toISOString() })
      }
      return res.data
    } catch (err) {
      const idx = sessionMsgs.indexOf(userMsg)
      if (idx >= 0) sessionMsgs.splice(idx, 1)
      throw err
    } finally {
      loadingSessionId.value = null
    }
  }

  async function sendMessageStream(message: string, onChunk: (text: string) => void) {
    const sessionId = currentSessionId.value!
    loadingSessionId.value = sessionId

    const sessionMsgs = getOrCreateSessionMessages(sessionId)
    const userMsg: Message = { id: 0, role: 'USER', content: message, createdAt: new Date().toISOString() }
    const assistantMsg: Message = { id: 0, role: 'ASSISTANT', content: '', createdAt: new Date().toISOString() }
    sessionMsgs.push(userMsg)
    sessionMsgs.push(assistantMsg)

    const controller = new AbortController()
    abortController.value = controller
    toolsInUse.value = []

    try {
      const response = await createSSEFetch(sessionId, message, controller)
      await readSSEStream(response, assistantMsg, sessionId)
    } catch (err: any) {
      if (err.name !== 'AbortError') throw err
    } finally {
      abortController.value = null
      toolsInUse.value = []
      loadingSessionId.value = null
    }
  }

  async function deleteMessage(messageId: number) {
    await api.delete(`/chat/messages/${messageId}`)
    if (currentSessionId.value !== null) {
      const msgs = getOrCreateSessionMessages(currentSessionId.value)
      const idx = msgs.findIndex(m => m.id === messageId)
      if (idx >= 0) msgs.splice(idx, 1)
    }
  }

  async function deleteMessagesAfter(sessionId: number, messageId: number) {
    await api.delete(`/chat/sessions/${sessionId}/messages/after/${messageId}`)
    const msgs = getOrCreateSessionMessages(sessionId)
    const idx = msgs.findIndex(m => m.id === messageId)
    if (idx >= 0) {
      msgs.splice(idx + 1)
    }
  }

  async function editAndResend(messageId: number, newContent: string) {
    const sessionId = currentSessionId.value!
    await api.put(`/chat/messages/${messageId}`, { content: newContent })
    await api.delete(`/chat/sessions/${sessionId}/messages/after/${messageId}`)
    const msgs = getOrCreateSessionMessages(sessionId)
    const idx = msgs.findIndex(m => m.id === messageId)
    if (idx >= 0) {
      msgs[idx].content = newContent
      msgs.splice(idx + 1)
    }

    loadingSessionId.value = sessionId
    const assistantMsg: Message = { id: 0, role: 'ASSISTANT', content: '', createdAt: new Date().toISOString() }
    msgs.push(assistantMsg)

    const controller = new AbortController()
    abortController.value = controller
    toolsInUse.value = []

    try {
      const response = await createSSEFetch(sessionId, newContent, controller)
      await readSSEStream(response, assistantMsg, sessionId)
    } catch (err: any) {
      if (err.name !== 'AbortError') throw err
    } finally {
      abortController.value = null
      toolsInUse.value = []
      loadingSessionId.value = null
    }
  }

  async function regenerateLastReply() {
    const sessionId = currentSessionId.value!
    const msgs = getOrCreateSessionMessages(sessionId)

    let lastAssistantIdx = -1
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === 'ASSISTANT') { lastAssistantIdx = i; break }
    }
    if (lastAssistantIdx < 0) return

    let lastUserMsg = ''
    for (let i = lastAssistantIdx - 1; i >= 0; i--) {
      if (msgs[i].role === 'USER') { lastUserMsg = msgs[i].content; break }
    }
    if (!lastUserMsg) return

    const lastAssistant = msgs[lastAssistantIdx]
    if (lastAssistant.id > 0) {
      await api.delete(`/chat/messages/${lastAssistant.id}`)
    }
    msgs.splice(lastAssistantIdx, 1)

    loadingSessionId.value = sessionId
    const assistantMsg: Message = { id: 0, role: 'ASSISTANT', content: '', createdAt: new Date().toISOString() }
    msgs.push(assistantMsg)

    const controller = new AbortController()
    abortController.value = controller
    toolsInUse.value = []

    try {
      const response = await createSSEFetch(sessionId, lastUserMsg, controller)
      await readSSEStream(response, assistantMsg, sessionId)
    } catch (err: any) {
      if (err.name !== 'AbortError') throw err
    } finally {
      abortController.value = null
      toolsInUse.value = []
      loadingSessionId.value = null
    }
  }

  async function cancelStream() {
    if (abortController.value) {
      abortController.value.abort()
      abortController.value = null
    }
    if (currentSessionId.value) {
      try {
        await api.post('/chat/stream/cancel', { sessionId: currentSessionId.value })
      } catch { /* ignore */ }
    }
    toolsInUse.value = []
    loadingSessionId.value = null
  }

  async function deleteSession(sessionId: number) {
    await api.delete(`/chat/sessions/${sessionId}`)
    sessions.value = sessions.value.filter(s => s.id !== sessionId)
    sessionMessagesCache.value.delete(sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
    }
  }

  return {
    sessions, currentSessionId, messages, loadingSessionId, isCurrentSessionLoading,
    abortController, toolsInUse,
    loadSessions, createSession, loadMessages, sendMessage, sendMessageStream,
    deleteSession, deleteMessage, deleteMessagesAfter, editAndResend,
    regenerateLastReply, cancelStream
  }
})
