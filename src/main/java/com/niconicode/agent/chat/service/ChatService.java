package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.ChatReq;
import com.niconicode.agent.chat.dto.ChatResp;
import com.niconicode.agent.chat.dto.ConversationContext;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.agent.tracker.service.TrackerService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingModel;
    private final MemoryService memoryService;
    private final ToolExecutionService toolService;
    private final RagService ragService;
    private final TrackerService trackerService;
    private final IntentClassifier intentClassifier;
    private final QueryRewriter queryRewriter;
    private final TraceLogger traceLogger;
    private final ConversationContextBuilder contextBuilder;

    @Value("${ai.tools.enabled:true}")
    private boolean toolsEnabled;

    private final ConcurrentHashMap<Long, SseEmitter> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, StringBuilder> activeStreamContents = new ConcurrentHashMap<>();

    private static final int MAX_TOOL_ITERATIONS = 2;

    private static final String SYSTEM_PROMPT = """
            你是 NiCoNiCode 的 AI 技术助手，专注于编程和技术领域的问答。
            你的特点：
            1. 精通主流编程语言和框架
            2. 能够搜索知识库获取准确的技术文档和教程
            3. 能够查询技术追踪系统了解最新版本和更新动态
            4. 回答准确、条理清晰，善用代码示例
            注意事项：
            - 当前日期：%s
            - 使用中文回答
            - 代码使用 Markdown 格式
            - 不确定的内容要如实说明
            - 当用户询问特定技术的版本、更新、文档时，优先使用工具获取准确信息
            - 如果系统提示中包含"知识库参考资料"，请综合利用这些背景知识，工具的实时数据优先级更高
            - 简单的问候或通用问题无需使用工具，直接回答即可
            - 使用工具搜索到的内容要注明来源
            - 当用户说"今天"、"最近"等时间词时，以当前日期为基准
            """;

    public ChatResp processMessage(Long userId, ChatReq req) {
        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());
        boolean isFirstMessage = isFirstMessage(session.getId());

        TraceLogger.TraceContext traceCtx = traceLogger.startTrace(userId, session.getId());

        ConversationContext ctx = contextBuilder.build(userId, session);
        List<dev.langchain4j.data.message.ChatMessage> messages =
                ctx.toLangChainMessages(SYSTEM_PROMPT.formatted(java.time.LocalDate.now().toString()), req.getMessage());

        String reply;
        try {
            if (toolsEnabled) {
                reply = executeWithTools(new ArrayList<>(messages));
            } else {
                String ragContext = ragService.retrieveContext(req.getMessage());
                if (ragContext != null && !ragContext.isBlank()) {
                    injectRagContext(messages, ragContext);
                }
                ChatResponse response = chatModel.chat(messages);
                reply = response.aiMessage().text();
            }
        } catch (Exception e) {
            log.error("AI chat failed", e);
            reply = "抱歉，AI 服务暂时不可用，请稍后重试。";
            traceLogger.traceError(traceCtx, "CHAT_NON_STREAM", e);
        }

        memoryService.saveMessage(session.getId(), "USER", req.getMessage());
        memoryService.saveMessage(session.getId(), "ASSISTANT", reply);

        // 异步摘要压缩（非流式也执行，保持行为一致）
        CompletableFuture.runAsync(() -> {
            try {
                traceLogger.trace(traceCtx, "MEMORY_COMPRESS", "start");
                memoryService.compressMemoryIfNeeded(session.getId(), traceCtx);
                traceLogger.trace(traceCtx, "MEMORY_COMPRESS", "done");
            } catch (Exception e) {
                traceLogger.traceError(traceCtx, "MEMORY_COMPRESS", e);
            }
        });

        String sessionTitle = session.getTitle();
        if (isFirstMessage && "新对话".equals(sessionTitle)) {
            sessionTitle = generateSessionTitle(req.getMessage(), reply);
            memoryService.updateSessionTitle(session.getId(), sessionTitle);
        }

        traceLogger.endTrace(traceCtx);

        return ChatResp.builder()
                .sessionId(session.getId())
                .reply(reply)
                .sessionTitle(sessionTitle)
                .build();
    }

    public SseEmitter processMessageStream(Long userId, ChatReq req) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟总超时

        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());
        TraceLogger.TraceContext traceCtx = traceLogger.startTrace(userId, session.getId());

        ConversationContext ctx = contextBuilder.build(userId, session);
        List<dev.langchain4j.data.message.ChatMessage> messages =
                ctx.toLangChainMessages(SYSTEM_PROMPT.formatted(java.time.LocalDate.now().toString()), req.getMessage());

        traceLogger.traceUserMessage(traceCtx, req.getMessage());

        StringBuilder fullReply = new StringBuilder();
        AtomicBoolean emitterDead = new AtomicBoolean(false);

        activeStreams.put(session.getId(), emitter);
        activeStreamContents.put(session.getId(), fullReply);

        Runnable cleanup = () -> {
            emitterDead.set(true);
            activeStreams.remove(session.getId());
            activeStreamContents.remove(session.getId());
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            if (!fullReply.isEmpty()) {
                memoryService.saveMessage(session.getId(), "ASSISTANT", fullReply.toString());
            }
            cleanup.run();
        });

        if (toolsEnabled) {
            CompletableFuture.runAsync(() -> {
                try {
                    // 1. 噪音过滤 (instant)
                    if (intentClassifier.route(req.getMessage()).getDecision() == IntentClassifier.RouteDecision.NOISE_SKIP) {
                        memoryService.saveMessage(session.getId(), "USER", req.getMessage());
                        traceLogger.trace(traceCtx, "ROUTE", "Noise skip");
                        completeWithDone(emitter, session);
                        traceLogger.endTrace(traceCtx);
                        return;
                    }

                    // 2. 上下文感知 AI 意图识别 (所有非噪音消息)
                    long intentStart = System.currentTimeMillis();
                    String intentContext = ctx.toIntentContextString();
                    IntentClassifier.IntentResult intentResult;
                    try {
                        intentResult = intentClassifier.classify(req.getMessage(), intentContext);
                    } catch (Exception e) {
                        log.warn("Intent classification failed, defaulting to GENERAL_CHAT: {}", e.getMessage());
                        intentResult = new IntentClassifier.IntentResult();
                    }
                    int intentDuration = (int)(System.currentTimeMillis() - intentStart);
                    traceLogger.traceIntentClassification(traceCtx, intentResult.getIntent().name(), intentDuration);

                    // 回写意图结果到上下文
                    ctx.setLastIntentResult(intentResult);

                    // 保存用户消息（附带意图元数据）
                    String userMeta = "{\"intent\":\"" + intentResult.getIntent()
                            + "\",\"confidence\":" + intentResult.getConfidence() + "}";
                    memoryService.saveMessage(session.getId(), "USER", req.getMessage(), userMeta);

                    // 检查 emitter 是否还活着
                    if (emitterDead.get()) {
                        traceLogger.trace(traceCtx, "ABORT", "Emitter dead after intent classification");
                        traceLogger.endTrace(traceCtx);
                        return;
                    }

                    // 3. UNCLEAR + 低置信度 → 主动澄清
                    if (intentResult.getIntent() == IntentClassifier.Intent.UNCLEAR
                            && intentResult.getClarification() != null) {
                        sendClarificationResponse(emitter, session, intentResult.getClarification(), fullReply, traceCtx);
                        return;
                    }

                    // 4. 注入意图信息到系统提示
                    injectIntentContext(messages, intentResult);

                    // 5. 按意图路由
                    if (IntentClassifier.needsToolResolution(intentResult.getIntent())) {
                        // 复杂意图: 重写 → [工具调用 + RAG 并行] → 流式
                        long rewriteStart = System.currentTimeMillis();
                        QueryRewriter.RewriteResult rewrite = null;
                        try {
                            rewrite = queryRewriter.rewrite(req.getMessage(), intentContext);
                            int rewriteDuration = (int)(System.currentTimeMillis() - rewriteStart);
                            traceLogger.traceQueryRewrite(traceCtx, req.getMessage(), rewrite.getRewritten(), rewriteDuration);
                            if (!rewrite.getRewritten().equals(req.getMessage())) {
                                messages.set(messages.size() - 1,
                                        UserMessage.from(rewrite.getRewritten()));
                            }
                        } catch (Exception e) {
                            log.warn("Query rewrite failed, using original query: {}", e.getMessage());
                            traceLogger.trace(traceCtx, "QUERY_REWRITE", "Failed, using original");
                        }

                        if (emitterDead.get()) {
                            traceLogger.trace(traceCtx, "ABORT", "Emitter dead after rewrite");
                            traceLogger.endTrace(traceCtx);
                            return;
                        }

                        // RAG 与工具调用并行：RAG 检索在后台线程执行，工具调用在当前线程执行
                        final String ragQuery = rewrite != null ? rewrite.getRewritten() : req.getMessage();
                        final String intentName = intentResult.getIntent().name();
                        CompletableFuture<String> ragFuture = CompletableFuture.supplyAsync(() -> {
                            try {
                                long ragStart = System.currentTimeMillis();
                                String ragCtx = ragService.retrieveContext(ragQuery, intentName);
                                int ragDuration = (int)(System.currentTimeMillis() - ragStart);
                                if (ragCtx != null && !ragCtx.isBlank()) {
                                    traceLogger.trace(traceCtx, "RAG_RETRIEVE",
                                            "intent=" + intentName + ", chars=" + ragCtx.length() + ", duration=" + ragDuration + "ms");
                                } else {
                                    traceLogger.trace(traceCtx, "RAG_RETRIEVE", "no results, duration=" + ragDuration + "ms");
                                }
                                return ragCtx;
                            } catch (Exception e) {
                                log.warn("RAG retrieval failed: {}", e.getMessage());
                                traceLogger.trace(traceCtx, "RAG_RETRIEVE", "failed: " + e.getMessage());
                                return "";
                            }
                        });

            traceLogger.trace(traceCtx, "PIPELINE", "before_tool_resolution");
                        // 工具调用在当前线程同步执行
            long toolResolveStart = System.currentTimeMillis();
            List<dev.langchain4j.data.message.ChatMessage> resolvedMessages =
                resolveToolCalls(new ArrayList<>(messages), emitter, traceCtx);
            int toolResolveDuration = (int)(System.currentTimeMillis() - toolResolveStart);
            traceLogger.trace(traceCtx, "TOOL_RESOLVE", "duration=" + toolResolveDuration + "ms");

                        if (emitterDead.get()) {
                            traceLogger.trace(traceCtx, "ABORT", "Emitter dead after tool resolution");
                            traceLogger.endTrace(traceCtx);
                            return;
                        }

                        // 等待 RAG 结果（工具调用通常比 RAG 慢，此处通常已完成）
                        long ragWaitStart = System.currentTimeMillis();
                        String ragContext = ragFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
                        int ragWaitDuration = (int)(System.currentTimeMillis() - ragWaitStart);
                        traceLogger.trace(traceCtx, "RAG_WAIT", "duration=" + ragWaitDuration + "ms");
                        if (ragContext != null && !ragContext.isBlank()) {
                            injectRagContext(resolvedMessages, ragContext);
                        }

                        traceLogger.traceStreamingStart(traceCtx);
                        startStreaming(resolvedMessages, emitter, session, fullReply, traceCtx, req.getMessage());
                    } else {
                        // 简单意图 (GENERAL_CHAT): RAG 检索后流式输出
                        try {
                            long ragStart = System.currentTimeMillis();
                            String ragContext = ragService.retrieveContext(req.getMessage(), intentResult.getIntent().name());
                            int ragDuration = (int)(System.currentTimeMillis() - ragStart);
                            if (ragContext != null && !ragContext.isBlank()) {
                                traceLogger.trace(traceCtx, "RAG_RETRIEVE",
                                        "GENERAL_CHAT, chars=" + ragContext.length() + ", duration=" + ragDuration + "ms");
                                injectRagContext(messages, ragContext);
                            }
                        } catch (Exception e) {
                            log.warn("RAG retrieval failed for GENERAL_CHAT: {}", e.getMessage());
                        }
                        traceLogger.traceStreamingStart(traceCtx);
                        startStreaming(messages, emitter, session, fullReply, traceCtx, req.getMessage());
                    }
                } catch (Exception e) {
                    log.error("Tool resolution or streaming failed", e);
                    traceLogger.traceError(traceCtx, "TOOL_RESOLUTION", e);
                    traceLogger.endTrace(traceCtx);
                    try {
                        if (!fullReply.isEmpty()) {
                            memoryService.saveMessage(session.getId(), "ASSISTANT", fullReply.toString());
                        }
                        emitter.send(SseEmitter.event().name("error").data("AI 服务异常"));
                        emitter.complete();
                    } catch (Exception ex) {
                        // emitter 已完成，忽略
                    }
                }
            });
        } else {
            memoryService.saveMessage(session.getId(), "USER", req.getMessage());
            String ragContext = ragService.retrieveContext(req.getMessage(), null);
            if (ragContext != null && !ragContext.isBlank()) {
                traceLogger.trace(traceCtx, "RAG_RETRIEVE", "Retrieved " + ragContext.length() + " chars");
                injectRagContext(messages, ragContext);
            }
            traceLogger.traceStreamingStart(traceCtx);
            startStreaming(messages, emitter, session, fullReply, traceCtx, req.getMessage());
        }

        return emitter;
    }

    public void cancelStream(Long sessionId, Long userId) {
        SseEmitter emitter = activeStreams.remove(sessionId);
        StringBuilder content = activeStreamContents.remove(sessionId);

        if (emitter != null) {
            if (content != null && !content.isEmpty()) {
                memoryService.saveMessage(sessionId, "ASSISTANT", content.toString());
            }
            emitter.complete();
        }
    }

    // ---- Tool Execution (Phase 1: 同步) ----

    private String executeWithTools(List<dev.langchain4j.data.message.ChatMessage> messages) {
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .parameters(DefaultChatRequestParameters.builder()
                            .toolSpecifications(toolService.getToolSpecifications())
                            .build())
                    .build();
            ChatResponse response = chatModel.chat(request);
            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text();
            }

            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                String result = toolService.executeTool(req);
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }
        // 超过最大迭代，强制生成回答
        return chatModel.chat(messages).aiMessage().text();
    }

    private List<dev.langchain4j.data.message.ChatMessage> resolveToolCalls(
            List<dev.langchain4j.data.message.ChatMessage> messages, SseEmitter emitter,
            TraceLogger.TraceContext traceCtx) {
        final long totalStart = System.currentTimeMillis();
        // 总预算 10s，单次 LLM 调用 8s 硬超时（防止单次调用拖垮整个预算）
        final int maxTotalMs = 10_000;
        final int perCallTimeoutMs = 8_000;

        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            long elapsed = System.currentTimeMillis() - totalStart;
            if (elapsed > maxTotalMs) {
                traceLogger.trace(traceCtx, "TOOL_RESOLVE", "budget_exceeded, stop resolving tools");
                break;
            }

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .parameters(DefaultChatRequestParameters.builder()
                            .toolSpecifications(toolService.getToolSpecifications())
                            .build())
                    .build();

            long planStart = System.currentTimeMillis();
            ChatResponse response;
            try {
                // 用 CompletableFuture 给 LLM 调用加硬超时
                response = CompletableFuture.supplyAsync(() -> chatModel.chat(request))
                        .get(perCallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                int planMs = (int)(System.currentTimeMillis() - planStart);
                traceLogger.trace(traceCtx, "TOOL_PLAN", "iteration=" + (i + 1) + ", TIMEOUT after " + planMs + "ms");
                break;
            } catch (Exception e) {
                int planMs = (int)(System.currentTimeMillis() - planStart);
                traceLogger.trace(traceCtx, "TOOL_PLAN", "iteration=" + (i + 1) + ", ERROR after " + planMs + "ms: " + e.getMessage());
                break;
            }
            int planMs = (int)(System.currentTimeMillis() - planStart);
            traceLogger.trace(traceCtx, "TOOL_PLAN", "iteration=" + (i + 1) + ", duration=" + planMs + "ms");

            AiMessage aiMessage = response.aiMessage();

            if (!aiMessage.hasToolExecutionRequests()) {
                break;
            }

            // 通知前端正在调用工具
            List<String> toolNames = aiMessage.toolExecutionRequests().stream()
                    .map(ToolExecutionRequest::name)
                    .distinct()
                    .toList();
            sendToolEvent(emitter, toolNames);

            // 执行工具并追加结果
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                if (System.currentTimeMillis() - totalStart > maxTotalMs) {
                    traceLogger.trace(traceCtx, "TOOL_RESOLVE", "budget_exceeded during tool exec, stop");
                    break;
                }

                long toolStart = System.currentTimeMillis();
                String result = toolService.executeTool(req);
                int toolDuration = (int)(System.currentTimeMillis() - toolStart);
                traceLogger.traceToolCall(traceCtx, req.name(), req.arguments(), result, toolDuration);
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }
        return messages;
    }

    private static final java.util.Map<String, String> TOOL_LABEL_MAP = java.util.Map.of(
            "getRecentReportsForTech", "查询近期报道",
            "getReportsByDate", "按日期查询报道",
            "searchReports", "搜索报道",
            "getTechInfo", "查询技术信息",
            "listTrackedTechnologies", "列出追踪技术",
            "recordTechMention", "记录技术提及",
            "knowledgeSearch", "搜索知识库"
    );

    private void sendToolEvent(SseEmitter emitter, List<String> toolNames) {
        try {
            // 发送中文标签名，避免前端显示 "getRecentReportsForTech, getRecentReportsForTech..."
            String toolNamesJson = toolNames.stream()
                    .map(n -> "\"" + TOOL_LABEL_MAP.getOrDefault(n, n) + "\"")
                    .collect(Collectors.joining(",", "[", "]"));
            emitter.send(SseEmitter.event()
                    .name("tool")
                    .data("{\"tools\":" + toolNamesJson + "}"));
        } catch (Exception e) {
            log.warn("Failed to send tool SSE event", e);
        }
    }

    // ---- Streaming (Phase 2) ----

    private void startStreaming(List<dev.langchain4j.data.message.ChatMessage> messages,
                                SseEmitter emitter, ChatSession session, StringBuilder fullReply,
                                TraceLogger.TraceContext traceCtx, String originalUserMessage) {
        // 使用原子标志防止重复完成 emitter
        java.util.concurrent.atomic.AtomicBoolean emitterCompleted = new java.util.concurrent.atomic.AtomicBoolean(false);
        long streamStartTime = System.currentTimeMillis();

        // 心跳：有些代理/浏览器会缓冲小包，定期发送一个很小的 SSE 事件强制刷出。
        // 该事件前端可忽略。
        java.util.concurrent.ScheduledExecutorService heartbeatScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "sse-heartbeat-" + session.getId());
                    t.setDaemon(true);
                    return t;
                });
        java.util.concurrent.atomic.AtomicLong lastSendAt = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (emitterCompleted.get()) return;
            // 如果最近 2s 内已经有数据发送，就不额外发心跳
            if (System.currentTimeMillis() - lastSendAt.get() < 2000) return;
            try {
                emitter.send(SseEmitter.event().name("ping").data(""));
                lastSendAt.set(System.currentTimeMillis());
            } catch (Exception e) {
                emitterCompleted.set(true);
            }
        }, 1500, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);

        streamingModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (emitterCompleted.get()) return; // 已完成，跳过
                fullReply.append(partialResponse);
                try {
                    // JSON 编码避免 SSE 换行符丢失
                    String jsonSafe = partialResponse
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t");
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data("{\"t\":\"" + jsonSafe + "\"}"));
                    lastSendAt.set(System.currentTimeMillis());
                } catch (Exception e) {
                    log.warn("SSE send failed", e);
                    // 如果发送失败，标记为已完成避免后续重复调用
                    emitterCompleted.set(true);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                String finalReply = fullReply.toString();
                memoryService.saveMessage(session.getId(), "ASSISTANT", finalReply);
                int streamDuration = (int)(System.currentTimeMillis() - streamStartTime);
                traceLogger.traceStreamingComplete(traceCtx, finalReply.length(), streamDuration);
                traceLogger.endTrace(traceCtx);

                heartbeatScheduler.shutdownNow();
                activeStreams.remove(session.getId());
                activeStreamContents.remove(session.getId());

                // 自动生成标题 (首条消息)
                if ("新对话".equals(session.getTitle())) {
                    String newTitle = generateSessionTitle(originalUserMessage, finalReply);
                    memoryService.updateSessionTitle(session.getId(), newTitle);
                    session.setTitle(newTitle);
                }

                // 异步提取关键词（带 traceId）
                String currentTitle = session.getTitle();
                extractAndRecordMentions(traceCtx, currentTitle, originalUserMessage, finalReply);
                // 异步摘要压缩
                CompletableFuture.runAsync(() -> {
                    try {
                        traceLogger.trace(traceCtx, "MEMORY_COMPRESS", "start");
                        memoryService.compressMemoryIfNeeded(session.getId(), traceCtx);
                        traceLogger.trace(traceCtx, "MEMORY_COMPRESS", "done");
                    } catch (Exception e) {
                        log.warn("Memory compression failed for session {}", session.getId(), e);
                        traceLogger.traceError(traceCtx, "MEMORY_COMPRESS", e);
                    }
                });

                // 只在第一次调用时完成 emitter
                if (!emitterCompleted.getAndSet(true)) {
                    try {
                        String titleJson = escapeJson(session.getTitle());
                        emitter.send(SseEmitter.event()
                                .name("done")
                                .data("{\"sessionId\":" + session.getId() + ",\"sessionTitle\":\"" + titleJson + "\"}"));
                        lastSendAt.set(System.currentTimeMillis());
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("SSE complete failed", e);
                    }
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("Streaming error", error);
                traceLogger.traceError(traceCtx, "STREAMING", error);
                traceLogger.endTrace(traceCtx);
                if (!fullReply.isEmpty()) {
                    memoryService.saveMessage(session.getId(), "ASSISTANT", fullReply.toString());
                }

                heartbeatScheduler.shutdownNow();
                activeStreams.remove(session.getId());
                activeStreamContents.remove(session.getId());

                // 只在第一次调用时完成 emitter
                if (!emitterCompleted.getAndSet(true)) {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data("AI 服务异常"));
                        lastSendAt.set(System.currentTimeMillis());
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("SSE error completion failed", e);
                    }
                }
            }
        });
    }

    // ---- Message Building ----

    /**
     * 注入意图分析到系统提示，让 AI 知道分类结果后回答更精准
     */
    private void injectIntentContext(List<dev.langchain4j.data.message.ChatMessage> messages,
                                      IntentClassifier.IntentResult intentResult) {
        if (messages.isEmpty()) return;
        SystemMessage original = (SystemMessage) messages.get(0);
        String intentInfo = "\n\n--- 意图分析 ---\n" +
                "用户意图: " + intentResult.getIntent().name() + "\n" +
                "置信度: " + (int)(intentResult.getConfidence() * 100) + "%\n" +
                "提及的实体: " + intentResult.getEntities() + "\n" +
                "请根据以上意图分析给出合适回答。";
        messages.set(0, SystemMessage.from(original.text() + intentInfo));
    }

    /**
     * UNCLEAR 意图时返回澄清问题
     */
    private void sendClarificationResponse(SseEmitter emitter, ChatSession session,
                                            String clarification, StringBuilder fullReply,
                                            TraceLogger.TraceContext traceCtx) {
        try {
            String reply = "我不太确定您的意思，" + clarification;
            fullReply.append(reply);
            memoryService.saveMessage(session.getId(), "ASSISTANT", reply);
            traceLogger.trace(traceCtx, "CLARIFICATION", clarification);
            traceLogger.endTrace(traceCtx);

            String jsonSafe = reply.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");
            emitter.send(SseEmitter.event().name("message").data("{\"t\":\"" + jsonSafe + "\"}"));
            completeWithDone(emitter, session);
        } catch (Exception e) {
            log.warn("Failed to send clarification", e);
        }
    }

    /**
     * 发送 done 事件并完成 emitter（含 sessionTitle）
     */
    private void completeWithDone(SseEmitter emitter, ChatSession session) {
        try {
            String titleJson = escapeJson(session.getTitle());
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data("{\"sessionId\":" + session.getId() + ",\"sessionTitle\":\"" + titleJson + "\"}"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE complete failed", e);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void injectRagContext(List<dev.langchain4j.data.message.ChatMessage> messages, String ragContext) {
        if (messages.isEmpty() || ragContext == null || ragContext.isBlank()) return;
        SystemMessage original = (SystemMessage) messages.get(0);
        String injection = """

                --- 知识库参考资料 ---
                以下内容来自知识库的语义检索结果，可作为背景知识辅助回答。
                请综合以下知识库内容和工具调用结果给出全面的回答，优先以工具的实时数据为准，
                知识库内容作为补充背景，若两者冲突以工具结果为准：

                """ + ragContext + "\n--- 知识库参考资料结束 ---";
        messages.set(0, SystemMessage.from(original.text() + injection));
    }

    private boolean isFirstMessage(Long sessionId) {
        List<ChatMessage> messages = memoryService.getSessionMessagesDirectly(sessionId);
        return messages == null || messages.isEmpty();
    }

    private String generateSessionTitle(String userMessage, String aiReply) {
        String title = userMessage;
        int questionMarkIdx = userMessage.indexOf('?');
        if (questionMarkIdx > 0) {
            title = userMessage.substring(0, questionMarkIdx);
        } else if (userMessage.length() > 20) {
            title = userMessage.substring(0, 20);
        }
        return title;
    }

    /**
     * 异步提取用户消息中提到的技术关键词，记录到热点话题
     * 双通道：1) 模式匹配已追踪技术名称 2) AI 工具自主记录（已在 TechTrackerTools 中实现）
     */
    private void extractAndRecordMentions(TraceLogger.TraceContext traceCtx,
                                          String sessionTitle,
                                          String userMessage,
                                          String aiReply) {
        CompletableFuture.runAsync(() -> {
            try {
                String context = (sessionTitle != null ? sessionTitle : "")
                        + "\n" + (userMessage != null ? userMessage : "")
                        + "\n" + (aiReply != null ? aiReply : "");

                // 1) 仍保留：对已追踪技术的快速匹配（低成本、低误报）
                List<String> techNames = trackerService.getAllTechNames();
                String contextLower = context.toLowerCase();
                int hit = 0;
                for (String name : techNames) {
                    if (name != null && !name.isBlank() && contextLower.contains(name.toLowerCase())) {
                        trackerService.recordMention(name);
                        hit++;
                    }
                }

                // 2) 新增：从文本中抽取候选技术名（简单规则版：优先 GitHub owner/repo，避免大模型误判）
                // 说明：后续可以再引入 fastChatModel 做 NER，这里先把"可落库的确凿来源"打通。
        MentionExtractor.MentionDiagnostics md = MentionExtractor.extractMentionsWithDiagnostics(context, 10);
        List<String> extracted = md.mentions;
                for (String kw : extracted) {
                    trackerService.recordMention(kw);
                }

        String diag = (md.diagnostics == null || md.diagnostics.isEmpty())
            ? "none"
            : String.join(" | ", md.diagnostics);
        if (diag.length() > 500) {
            diag = diag.substring(0, 500) + "...";
        }

        traceLogger.trace(traceCtx, "MENTION_EXTRACT",
            "trackedHits=" + hit + ", extracted=" + extracted.size() + ", diag=" + diag);
            } catch (Exception e) {
                traceLogger.traceError(traceCtx, "MENTION_EXTRACT", e);
            }
        });
    }
}
