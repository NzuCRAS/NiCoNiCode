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
  thinking?: ThinkingData
}

interface ThinkingData {
  intent: string
  subIntent: string
  confidence: number
  classifiedBy: string
  strategy: string
  rewrittenQuery?: string
  tools: { name: string; label: string }[]
  ragDocs: { id: number; title: string; score: number }[]
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<Session[]>([])
  const currentSessionId = ref<number | null>(null)
  const sessionMessagesCache = ref<Map<number, Message[]>>(new Map())
  const loadingSessionId = ref<number | null>(null)
  const abortController = ref<AbortController | null>(null)
  const toolsInUse = ref<string[]>([])
  const streamingStarted = ref(false)
  const thinkingData = ref<ThinkingData | null>(null)
  // 用于显式触发 UI 重绘（保险丝）：流式场景中 token 可能极碎，深层对象变更有时不会立刻驱动组件更新
  const renderTick = ref(0)
  const messages = computed(() => {
    // 访问一次 renderTick，让其成为 computed 依赖，确保每帧 flush 都会触发依赖更新
    // eslint-disable-next-line @typescript-eslint/no-unused-expressions
    renderTick.value
    if (currentSessionId.value === null) return []
  // 关键：返回一个新数组引用，避免 UI 只在数组引用变化时才重渲染
  const arr = sessionMessagesCache.value.get(currentSessionId.value) || []
  return arr.slice()
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

  /** 共享 SSE 流读取逻辑
   *  @param sessionMsgs  当前 session 的消息数组引用，用于延迟 push assistantMsg
   *  @param assistantMsg 预创建的 AI 气泡对象（未 push 进数组，等首个 token 再 push）
   */
  async function readSSEStream(
    response: Response,
    assistantMsg: Message,
    sessionId: number,
    sessionMsgs: Message[]
  ) {
    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''
  let currentData = ''
    // 工具名累积集合（跨轮去重）
    const toolsSeen = new Set<string>()

    // token 合并缓冲：避免每个极小 chunk 都触发一次响应式更新导致浏览器合并渲染、最后一次性显示
    let pendingText = ''
    let flushScheduled = false
    const scheduleFlush = () => {
      if (flushScheduled) return
      flushScheduled = true
      requestAnimationFrame(() => {
        flushScheduled = false
        if (!pendingText) return
        if (!streamingStarted.value) {
          streamingStarted.value = true
          // 附着思考链数据到 AI 消息
          if (thinkingData.value) {
            assistantMsg.thinking = thinkingData.value
          }
          sessionMsgs.push(assistantMsg)
        }
        assistantMsg.content += pendingText
        pendingText = ''
        renderTick.value++
      })
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        // 空行表示一个 SSE 事件结束
        if (line.trim() === '') {
          if (!currentEvent) {
            currentData = ''
            continue
          }

          const data = currentData
          if (currentEvent === 'tool') {
            try {
              const toolData = JSON.parse(data)
              const newTools: string[] = toolData.tools || []
              newTools.forEach(t => toolsSeen.add(t))
              toolsInUse.value = Array.from(toolsSeen)
            } catch { /* ignore */ }
          } else if (currentEvent === 'thinking') {
            try {
              thinkingData.value = JSON.parse(data)
            } catch { /* ignore */ }
          } else if (currentEvent === 'done') {
            toolsInUse.value = []
            toolsSeen.clear()
            try {
              const json = JSON.parse(data)
              if (currentSessionId.value === sessionId) {
                currentSessionId.value = json.sessionId
              }
              if (json.sessionTitle) {
                const s = sessions.value.find(s => s.id === json.sessionId)
                if (s) {
                  s.title = json.sessionTitle
                } else {
                  sessions.value.unshift({ id: json.sessionId, title: json.sessionTitle, updatedAt: new Date().toISOString() })
                }
              }
            } catch { /* ignore */ }
            } else if (currentEvent === 'ping') {
              // 服务器心跳，用于强制代理/浏览器刷新缓冲；前端无需处理
          } else if (currentEvent === 'error') {
            toolsInUse.value = []
            toolsSeen.clear()
          } else if (currentEvent === 'message') {
            toolsInUse.value = []
            toolsSeen.clear()
            pendingText += extractMessageText(data)
            scheduleFlush()
          }

          currentEvent = ''
          currentData = ''
          continue
        }

        if (line.startsWith('event:')) {
          currentEvent = line.substring(6).trim()
        } else if (line.startsWith('data:')) {
          const chunk = line.substring(5)
          // 同一事件可能多行 data，按 SSE 规范用 '\n' 连接
          currentData += (currentData ? '\n' : '') + chunk
        }
      }
    }

    // 读取结束时，确保把最后一段 pendingText 刷出
    if (pendingText) {
      if (!streamingStarted.value) {
        streamingStarted.value = true
        sessionMsgs.push(assistantMsg)
      }
      assistantMsg.content += pendingText
      pendingText = ''
      renderTick.value++
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
    // assistantMsg 不提前 push，等收到第一个 token 再由 readSSEStream 内部 push
    // 这样可以避免"思考中"气泡和空 AI 气泡同时显示
    const assistantMsg: Message = { id: 0, role: 'ASSISTANT', content: '', createdAt: new Date().toISOString() }
    sessionMsgs.push(userMsg)

    const controller = new AbortController()
    abortController.value = controller
    toolsInUse.value = []
    streamingStarted.value = false
    thinkingData.value = null

    try {
      const response = await createSSEFetch(sessionId, message, controller)
      await readSSEStream(response, assistantMsg, sessionId, sessionMsgs)
    } catch (err: any) {
      if (err.name !== 'AbortError') throw err
    } finally {
      abortController.value = null
      toolsInUse.value = []
      streamingStarted.value = false
      thinkingData.value = null
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
    // 不提前 push，readSSEStream 收到首个 token 时才 push

    const controller = new AbortController()
    abortController.value = controller
    toolsInUse.value = []
    streamingStarted.value = false
    thinkingData.value = null

    try {
      const response = await createSSEFetch(sessionId, newContent, controller)
      await readSSEStream(response, assistantMsg, sessionId, msgs)
    } catch (err: any) {
      if (err.name !== 'AbortError') throw err
    } finally {
      abortController.value = null
      toolsInUse.value = []
      streamingStarted.value = false
      thinkingData.value = null
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
    // 不提前 push，readSSEStream 收到首个 token 时才 push

    const controller = new AbortController()
    abortController.value = controller
    toolsInUse.value = []
    streamingStarted.value = false
    thinkingData.value = null

    try {
      const response = await createSSEFetch(sessionId, lastUserMsg, controller)
      await readSSEStream(response, assistantMsg, sessionId, msgs)
    } catch (err: any) {
      if (err.name !== 'AbortError') throw err
    } finally {
      abortController.value = null
      toolsInUse.value = []
      streamingStarted.value = false
      thinkingData.value = null
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
  sessions, currentSessionId, messages, renderTick, loadingSessionId, isCurrentSessionLoading,
    abortController, toolsInUse, streamingStarted, thinkingData,
    loadSessions, createSession, loadMessages, sendMessage, sendMessageStream,
    deleteSession, deleteMessage, deleteMessagesAfter, editAndResend,
    regenerateLastReply, cancelStream
  }
})
