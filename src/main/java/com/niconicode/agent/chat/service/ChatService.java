package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.ChatReq;
import com.niconicode.agent.chat.dto.ChatResp;
import com.niconicode.agent.chat.dto.ConversationContext;
import com.niconicode.agent.chat.dto.IntentClassification;
import com.niconicode.agent.chat.dto.SubTask;
import com.niconicode.agent.chat.graph.MemoryPipelineGraph;
import com.niconicode.agent.chat.graph.MemoryState;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private final ChatLanguageModel chatModel;
    private final ChatLanguageModel fastChatModel;
    private final StreamingChatLanguageModel streamingModel;
    private final MemoryService memoryService;
    private final ToolExecutionService toolService;
    private final RagService ragService;
    private final TrackerService trackerService;
    private final IntentClassifier intentClassifier;
    private final QueryRewriter queryRewriter;
    private final TraceLogger traceLogger;
    private final ConversationContextBuilder contextBuilder;
    private final SubTaskDagExecutor dagExecutor;
    private final MemoryPipelineGraph memoryPipelineGraph;
    private final ExecutionPlanner executionPlanner;

    public ChatService(ChatLanguageModel chatModel,
                       @Qualifier("fastChatModel") ChatLanguageModel fastChatModel,
                       StreamingChatLanguageModel streamingModel,
                       MemoryService memoryService,
                       ToolExecutionService toolService,
                       RagService ragService,
                       TrackerService trackerService,
                       IntentClassifier intentClassifier,
                       QueryRewriter queryRewriter,
                       TraceLogger traceLogger,
                       ConversationContextBuilder contextBuilder,
                       SubTaskDagExecutor dagExecutor,
                       MemoryPipelineGraph memoryPipelineGraph,
                       ExecutionPlanner executionPlanner) {
        this.chatModel = chatModel;
        this.fastChatModel = fastChatModel;
        this.streamingModel = streamingModel;
        this.memoryService = memoryService;
        this.toolService = toolService;
        this.ragService = ragService;
        this.trackerService = trackerService;
        this.intentClassifier = intentClassifier;
        this.queryRewriter = queryRewriter;
        this.traceLogger = traceLogger;
        this.contextBuilder = contextBuilder;
        this.dagExecutor = dagExecutor;
        this.memoryPipelineGraph = memoryPipelineGraph;
        this.executionPlanner = executionPlanner;
    }

    @Value("${ai.tools.enabled:true}")
    private boolean toolsEnabled;

    private final ConcurrentHashMap<Long, SseEmitter> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, StringBuilder> activeStreamContents = new ConcurrentHashMap<>();

    // 专用线程池：用于 LLM 调用超时控制。
    // 不使用 ForkJoinPool.commonPool()，因为 ForkJoinPool 的 work-stealing
    // 会将 supplyAsync 任务内联到调用线程执行，导致 get(timeout) 形同虚设。
    private final ExecutorService llmExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-timeout-pool");
        t.setDaemon(true);
        return t;
    });

    private static final int MAX_TOOL_ITERATIONS = 2;

    // 这些子意图的 GENERAL_CHAT 消息无需 RAG 检索
    private static final Set<IntentClassification.SubIntent> SKIP_RAG_INTENTS = Set.of(
            IntentClassification.SubIntent.GREETING,
            IntentClassification.SubIntent.FAREWELL,
            IntentClassification.SubIntent.THANKS,
            IntentClassification.SubIntent.CONFIRMATION
    );

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
            - 当引用具体报道或知识文档时，保留原文中的 [报道#ID] 或 [文档#ID] 标记，不要删除或改写这些标记
            """;

    public ChatResp processMessage(Long userId, ChatReq req) {
        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());
        boolean isFirstMessage = isFirstMessage(session.getId());

        TraceLogger.TraceContext traceCtx = traceLogger.startTrace(userId, session.getId());

        ConversationContext ctx = contextBuilder.build(userId, session);
        List<dev.langchain4j.data.message.ChatMessage> messages =
                ctx.toLangChainMessages(SYSTEM_PROMPT.replace("%s", java.time.LocalDate.now().toString()), req.getMessage());

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
        final String finalReplyForMemory = reply;
        CompletableFuture.runAsync(() -> {
            try {
                traceLogger.trace(traceCtx, "MEMORY_COMPRESS", "start");
                memoryService.compressMemoryIfNeeded(session.getId(), traceCtx);
                traceLogger.trace(traceCtx, "MEMORY_COMPRESS", "done");
            } catch (Exception e) {
                traceLogger.traceError(traceCtx, "MEMORY_COMPRESS", e);
            }
        });

        // 异步记忆提取（双层记忆）
        if (req.getMessage().length() > 5) {
            CompletableFuture.runAsync(() -> {
                try {
                    MemoryState memState = MemoryState.builder()
                            .userId(userId).sessionId(session.getId())
                            .currentUserMessage(req.getMessage())
                            .currentAiReply(finalReplyForMemory)
                            .recentMessages(ctx.getRecentMessages())
                            .traceCtx(traceCtx)
                            .build();
                    memoryPipelineGraph.executeMemoryExtraction(memState);
                } catch (Exception e) {
                    traceLogger.traceError(traceCtx, "MEMORY_PIPELINE", e);
                }
            });
        }

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
                ctx.toLangChainMessages(SYSTEM_PROMPT.replace("%s", java.time.LocalDate.now().toString()), req.getMessage());

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

                    // 2. 三级级联意图识别
                    String intentContext = ctx.toIntentContextString();
                    IntentClassification intentResult;
                    try {
                        intentResult = intentClassifier.classify(req.getMessage(), intentContext);
                    } catch (Exception e) {
                        log.warn("Intent classification failed, defaulting to GENERAL_CHAT: {}", e.getMessage());
                        intentResult = IntentClassification.fallback();
                    }
                    traceLogger.traceIntentClassification(traceCtx,
                            intentResult.getPrimaryIntent().name(),
                            intentResult.getSubIntent().name(),
                            intentResult.getClassifiedBy().name(),
                            (int) intentResult.getLatencyMs());

                    // 回写意图结果到上下文
                    ctx.setLastIntentClassification(intentResult);

                    // 保存用户消息（附带意图元数据）
                    String userMeta = "{\"intent\":\"" + intentResult.getPrimaryIntent()
                            + "\",\"subIntent\":\"" + intentResult.getSubIntent()
                            + "\",\"confidence\":" + intentResult.getConfidence()
                            + ",\"classifiedBy\":\"" + intentResult.getClassifiedBy() + "\"}";
                    memoryService.saveMessage(session.getId(), "USER", req.getMessage(), userMeta);

                    // 检查 emitter 是否还活着
                    if (emitterDead.get()) {
                        traceLogger.trace(traceCtx, "ABORT", "Emitter dead after intent classification");
                        traceLogger.endTrace(traceCtx);
                        return;
                    }

                    // 3. UNCLEAR + 低置信度 → 主动澄清
                    if (intentResult.getPrimaryIntent() == IntentClassification.Intent.UNCLEAR
                            && intentResult.getClarification() != null) {
                        sendClarificationResponse(emitter, session, intentResult.getClarification(), fullReply, traceCtx);
                        return;
                    }

                    // 4. 注入意图信息到系统提示
                    injectIntentContext(messages, intentResult);

                    // 5. 工作流规划（确定性，无 LLM 调用）
                    // 需要工具的意图先做 rewrite（用于日期解析、指代消解）
                    QueryRewriter.RewriteResult rewrite = null;
                    String rewrittenQuery = req.getMessage();
                    if (intentResult.needsToolResolution()) {
                        long rewriteStart = System.currentTimeMillis();
                        try {
                            rewrite = queryRewriter.rewrite(req.getMessage(), intentContext, intentResult, ctx);
                            int rewriteDuration = (int)(System.currentTimeMillis() - rewriteStart);
                            traceLogger.traceQueryRewriteEnhanced(traceCtx, req.getMessage(),
                                    rewrite.getRewritten(), rewrite.getStrategy().name(),
                                    rewrite.getSubTasks().size(), rewriteDuration);
                            rewrittenQuery = rewrite.getRewritten();
                            if (!rewrittenQuery.equals(req.getMessage())) {
                                messages.set(messages.size() - 1, UserMessage.from(rewrittenQuery));
                            }
                        } catch (Exception e) {
                            log.warn("Query rewrite failed, using original query: {}", e.getMessage());
                            traceLogger.trace(traceCtx, "QUERY_REWRITE", "Failed, using original");
                        }
                    }

                    List<SubTask> subTasks = rewrite != null ? rewrite.getSubTasks() : null;
                    ExecutionPlanner.WorkflowPlan plan = executionPlanner.plan(
                            intentResult, subTasks, rewrittenQuery);
                    traceLogger.trace(traceCtx, "EXEC_STRATEGY",
                            plan.getStrategy().name() + ", tools=" + plan.getToolCalls().size()
                                    + ", rag=" + plan.isNeedsRag());

                    if (emitterDead.get()) {
                        traceLogger.trace(traceCtx, "ABORT", "Emitter dead after plan");
                        traceLogger.endTrace(traceCtx);
                        return;
                    }

                    // 6. 执行工作流
                    switch (plan.getStrategy()) {
                        case DIRECT_ANSWER -> {
                            sendThinkingEvent(emitter, intentResult, plan, rewrittenQuery, req.getMessage(), null);
                            traceLogger.traceStreamingStart(traceCtx);
                            startStreaming(messages, emitter, session, fullReply, traceCtx, req.getMessage(), ctx);
                        }
                        case RAG_ONLY -> {
                            List<RagService.RagDoc> ragDocsMeta = null;
                            try {
                                long ragStart = System.currentTimeMillis();
                                RagService.RagResult ragResult = ragService.retrieveContextWithMeta(
                                        req.getMessage(), intentResult.getPrimaryIntent().name(),
                                        ctx.getRetrievedDocIds());
                                int ragDuration = (int)(System.currentTimeMillis() - ragStart);
                                String ragContext = ragResult.getContext();
                                ragDocsMeta = ragResult.getDocs();
                                if (ragContext != null && !ragContext.isBlank()) {
                                    traceLogger.trace(traceCtx, "RAG_RETRIEVE",
                                            "RAG_ONLY, chars=" + ragContext.length() + ", duration=" + ragDuration + "ms");
                                    injectRagContext(messages, ragContext);
                                }
                            } catch (Exception e) {
                                log.warn("RAG retrieval failed: {}", e.getMessage());
                            }
                            sendThinkingEvent(emitter, intentResult, plan, rewrittenQuery, req.getMessage(), ragDocsMeta);
                            traceLogger.traceStreamingStart(traceCtx);
                            startStreaming(messages, emitter, session, fullReply, traceCtx, req.getMessage(), ctx);
                        }
                        case WORKFLOW -> {
                            executeWorkflow(plan, intentResult, messages, emitter, session,
                                    fullReply, traceCtx, req.getMessage(), ctx, rewrittenQuery);
                        }
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
            startStreaming(messages, emitter, session, fullReply, traceCtx, req.getMessage(), ctx);
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
                // 用专用线程池 + CompletableFuture 给 LLM 调用加硬超时
                // 注意：不能用 ForkJoinPool.commonPool()，因为 work-stealing 会导致超时失效
                response = CompletableFuture.supplyAsync(() -> chatModel.chat(request), llmExecutor)
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

            // 执行工具并追加结果（多个工具时并行执行）
            messages.add(aiMessage);
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            if (requests.size() > 1) {
                // 并行执行多个工具调用
                List<CompletableFuture<ToolExecutionResultMessage>> futures = requests.stream()
                        .map(req -> CompletableFuture.supplyAsync(() -> {
                            long toolStart = System.currentTimeMillis();
                            String result = toolService.executeTool(req);
                            int toolDuration = (int)(System.currentTimeMillis() - toolStart);
                            traceLogger.traceToolCall(traceCtx, req.name(), req.arguments(), result, toolDuration);
                            return ToolExecutionResultMessage.from(req, result);
                        }, llmExecutor))
                        .toList();
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(perCallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    traceLogger.trace(traceCtx, "TOOL_PARALLEL", "timeout/error: " + e.getMessage());
                }
                for (CompletableFuture<ToolExecutionResultMessage> f : futures) {
                    if (f.isDone() && !f.isCompletedExceptionally()) {
                        messages.add(f.join());
                    }
                }
            } else {
                // 单工具调用：顺序执行
                for (ToolExecutionRequest req : requests) {
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
        }
        return messages;
    }

    // ---- 工作流执行 ----

    /**
     * 工作流模式：确定性工具调用 + 可选 RAG，完全不经过 LLM 做工具选择。
     * 所有工具并行执行，结果注入上下文后一次流式生成回答。
     */
    private void executeWorkflow(ExecutionPlanner.WorkflowPlan plan,
                                  IntentClassification intentResult,
                                  List<dev.langchain4j.data.message.ChatMessage> messages,
                                  SseEmitter emitter, ChatSession session, StringBuilder fullReply,
                                  TraceLogger.TraceContext traceCtx, String originalMessage,
                                  ConversationContext ctx, String rewrittenQuery) {
        long workflowStart = System.currentTimeMillis();
        List<ExecutionPlanner.ToolCall> toolCalls = plan.getToolCalls();

        // 通知前端正在调用工具
        if (!toolCalls.isEmpty()) {
            List<String> toolNames = toolCalls.stream()
                    .map(ExecutionPlanner.ToolCall::getToolName).distinct().toList();
            sendToolEvent(emitter, toolNames);
        }

        // 并行执行所有工具 + 可选 RAG
        CompletableFuture<RagService.RagResult> ragFuture = plan.isNeedsRag()
                ? CompletableFuture.supplyAsync(() -> {
                    try {
                        return ragService.retrieveContextWithMeta(
                                originalMessage, intentResult.getPrimaryIntent().name(),
                                ctx.getRetrievedDocIds());
                    } catch (Exception e) {
                        log.warn("RAG in workflow failed: {}", e.getMessage());
                        return new RagService.RagResult(null, List.of());
                    }
                }, llmExecutor)
                : CompletableFuture.completedFuture(new RagService.RagResult(null, List.of()));

        List<CompletableFuture<String[]>> toolFutures = toolCalls.stream()
                .map(tc -> CompletableFuture.supplyAsync(() -> {
                    long ts = System.currentTimeMillis();
                    ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                            .name(tc.getToolName()).arguments(tc.getArguments()).build();
                    String result = toolService.executeTool(toolReq);
                    int dur = (int)(System.currentTimeMillis() - ts);
                    traceLogger.traceToolCall(traceCtx, tc.getToolName(), tc.getArguments(), result, dur);
                    return new String[]{tc.getToolName(), result};
                }, llmExecutor))
                .toList();

        // 等待所有工具完成（总超时 8s）
        StringBuilder toolResults = new StringBuilder();
        try {
            CompletableFuture.allOf(toolFutures.toArray(new CompletableFuture[0]))
                    .get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            traceLogger.trace(traceCtx, "WORKFLOW_TOOLS", "partial_timeout: " + e.getMessage());
        }

        for (CompletableFuture<String[]> f : toolFutures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                String[] pair = f.join();
                String toolName = pair[0];
                String result = pair[1];
                if (result != null && !result.isBlank()) {
                    toolResults.append("\n[").append(TOOL_LABEL_MAP.getOrDefault(toolName, toolName))
                            .append("]\n").append(smartTruncate(result, 2000)).append("\n");
                }
            }
        }

        // 注入工具结果
        if (!toolResults.isEmpty()) {
            String injection = "\n\n--- 工具调用结果（系统自动查询）---\n" + toolResults
                    + "\n--- 工具调用结果结束 ---";
            SystemMessage original = (SystemMessage) messages.get(0);
            messages.set(0, SystemMessage.from(original.text() + injection));
        }

        // 注入 RAG 结果
        List<RagService.RagDoc> ragDocsMeta = null;
        try {
            RagService.RagResult ragResult = ragFuture.get(3, java.util.concurrent.TimeUnit.SECONDS);
            ragDocsMeta = ragResult.getDocs();
            String ragContext = ragResult.getContext();
            if (ragContext != null && !ragContext.isBlank()) {
                injectRagContext(messages, ragContext);
                traceLogger.trace(traceCtx, "RAG_RETRIEVE",
                        "WORKFLOW, chars=" + ragContext.length());
            }
        } catch (Exception e) {
            log.warn("RAG wait failed in workflow: {}", e.getMessage());
        }

        // 发送思考链事件
        sendThinkingEvent(emitter, intentResult, plan, rewrittenQuery, originalMessage, ragDocsMeta);

        int workflowDuration = (int)(System.currentTimeMillis() - workflowStart);
        traceLogger.trace(traceCtx, "WORKFLOW_DONE",
                "tools=" + toolCalls.size() + ", duration=" + workflowDuration + "ms");

        traceLogger.traceStreamingStart(traceCtx);
        startStreaming(messages, emitter, session, fullReply, traceCtx, originalMessage, ctx);
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

    /**
     * 发送思考链 SSE 事件，让前端展示 Agent 推理过程
     */
    private void sendThinkingEvent(SseEmitter emitter,
                                    IntentClassification intentResult,
                                    ExecutionPlanner.WorkflowPlan plan,
                                    String rewrittenQuery,
                                    String originalMessage,
                                    List<RagService.RagDoc> ragDocs) {
        try {
            StringBuilder json = new StringBuilder("{");
            json.append("\"intent\":\"").append(escapeJson(intentResult.getPrimaryIntent().name())).append("\"");
            json.append(",\"subIntent\":\"").append(escapeJson(intentResult.getSubIntent().name())).append("\"");
            json.append(",\"confidence\":").append(intentResult.getConfidence());
            json.append(",\"classifiedBy\":\"").append(escapeJson(intentResult.getClassifiedBy().name())).append("\"");
            json.append(",\"strategy\":\"").append(escapeJson(plan.getStrategy().name())).append("\"");

            // 仅在重写后与原始不同时包含
            if (rewrittenQuery != null && !rewrittenQuery.equals(originalMessage)) {
                json.append(",\"rewrittenQuery\":\"").append(escapeJson(rewrittenQuery)).append("\"");
            }

            // 工具列表
            json.append(",\"tools\":[");
            List<ExecutionPlanner.ToolCall> toolCalls = plan.getToolCalls();
            for (int i = 0; i < toolCalls.size(); i++) {
                if (i > 0) json.append(",");
                String name = toolCalls.get(i).getToolName();
                String label = TOOL_LABEL_MAP.getOrDefault(name, name);
                json.append("{\"name\":\"").append(escapeJson(name))
                        .append("\",\"label\":\"").append(escapeJson(label)).append("\"}");
            }
            json.append("]");

            // RAG 文档列表
            json.append(",\"ragDocs\":[");
            if (ragDocs != null) {
                for (int i = 0; i < ragDocs.size(); i++) {
                    if (i > 0) json.append(",");
                    RagService.RagDoc doc = ragDocs.get(i);
                    json.append("{\"id\":").append(doc.getId())
                            .append(",\"title\":\"").append(escapeJson(doc.getTitle()))
                            .append("\",\"score\":").append(String.format("%.2f", doc.getScore())).append("}");
                }
            }
            json.append("]}");

            emitter.send(SseEmitter.event().name("thinking").data(json.toString()));
        } catch (Exception e) {
            log.warn("Failed to send thinking SSE event", e);
        }
    }

    // ---- Streaming (Phase 2) ----

    private void startStreaming(List<dev.langchain4j.data.message.ChatMessage> messages,
                                SseEmitter emitter, ChatSession session, StringBuilder fullReply,
                                TraceLogger.TraceContext traceCtx, String originalUserMessage,
                                ConversationContext ctx) {
    // 某些 provider 的 streaming 接口不支持 tool-role / tool_calls / ToolExecutionResultMessage，
    // 会直接报: "messages" in request are illegal.
    // 这里做一次兼容降级：将工具相关消息折叠为纯文本 SystemMessage，再进入 streaming。
    List<dev.langchain4j.data.message.ChatMessage> streamingMessages =
        sanitizeMessagesForStreaming(messages, traceCtx);

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

    streamingModel.chat(streamingMessages, new StreamingChatResponseHandler() {
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

                // 异步记忆提取（双层记忆）
                if (originalUserMessage.length() > 5) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            MemoryState memState = MemoryState.builder()
                                    .userId(session.getUserId()).sessionId(session.getId())
                                    .currentUserMessage(originalUserMessage)
                                    .currentAiReply(finalReply)
                                    .recentMessages(ctx.getRecentMessages())
                                    .traceCtx(traceCtx)
                                    .build();
                            memoryPipelineGraph.executeMemoryExtraction(memState);
                        } catch (Exception e) {
                            traceLogger.traceError(traceCtx, "MEMORY_PIPELINE", e);
                        }
                    });
                }

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

    /**
     * streaming provider 兼容层：将 tool 相关 message 转成文本。
     *
     * 目标：保证仅包含 system/user/assistant 三种角色的可序列化消息。
     */
    private List<dev.langchain4j.data.message.ChatMessage> sanitizeMessagesForStreaming(
            List<dev.langchain4j.data.message.ChatMessage> messages,
            TraceLogger.TraceContext traceCtx) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        int toolMsgCount = 0;
        boolean hasToolCalls = false;
        for (dev.langchain4j.data.message.ChatMessage m : messages) {
            if (m == null) continue;
            if (m instanceof ToolExecutionResultMessage) {
                toolMsgCount++;
            }
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                hasToolCalls = true;
            }
        }

        // 没有工具相关消息则不做任何处理
        if (toolMsgCount == 0 && !hasToolCalls) {
            traceLogger.trace(traceCtx, "STREAM_SANITIZE", "no_tool_messages");
            return messages;
        }

        StringBuilder toolTranscript = new StringBuilder();
        List<dev.langchain4j.data.message.ChatMessage> cleaned = new ArrayList<>(messages.size() + 1);

        for (dev.langchain4j.data.message.ChatMessage m : messages) {
            if (m == null) continue;

            if (m instanceof ToolExecutionResultMessage tr) {
                toolMsgCount++;
                toolTranscript.append("\n[TOOL_RESULT] ");
                // 不同版本 langchain4j 的 ToolExecutionResultMessage API 不一致，
                // 这里避免调用可能不存在的方法（如 toolExecutionRequest()）。
                toolTranscript.append("(tool execution result)\n");
                String r = tr.text();
                if (r != null && !r.isBlank()) {
                    // 纯文本智能截断（0ms）— 不在流式关键路径做 LLM 调用
                    String trimmed = smartTruncate(r, 2000);
                    toolTranscript.append(trimmed).append("\n");
                }
                continue; // drop tool-role message
            }

            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                hasToolCalls = true;
                toolTranscript.append("\n[TOOL_CALLS] ");
                toolTranscript.append(ai.toolExecutionRequests().stream().map(ToolExecutionRequest::name).distinct().collect(Collectors.joining(", ")));
                toolTranscript.append("\n");

                // 这条 assistant message 通常不包含最终回答，只是 tool planning。
                // 为兼容 streaming provider，这里不把它作为 assistant message 透传。
                // 如果它有可见文本（极少），也记录进去。
                if (ai.text() != null && !ai.text().isBlank()) {
                    toolTranscript.append(ai.text()).append("\n");
                }
                continue;
            }

            cleaned.add(m);
        }

        if (!toolTranscript.isEmpty()) {
            String injected = "\n\n--- 工具调用记录（系统整理）---\n" + toolTranscript;
            // 注入到 system message 末尾（保留原系统提示）
            if (!cleaned.isEmpty() && cleaned.get(0) instanceof SystemMessage sm) {
                cleaned.set(0, SystemMessage.from(sm.text() + injected));
            } else {
                cleaned.add(0, SystemMessage.from(injected));
            }
        }

        traceLogger.trace(traceCtx, "STREAM_SANITIZE",
                "toolMsgCount=" + toolMsgCount + ", hasToolCalls=" + hasToolCalls + ", before=" + messages.size() + ", after=" + cleaned.size());
        return cleaned;
    }

    // ---- Message Building ----

    /**
     * 智能截断工具结果（纯文本操作，0ms）。
     * 保留头部核心信息 + 尾部摘要，避免在流式关键路径做 LLM 调用。
     */
    private static String smartTruncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        // 保留前 70% + 后 20%，中间用省略标记
        int headLen = (int)(maxLen * 0.7);
        int tailLen = (int)(maxLen * 0.2);
        return text.substring(0, headLen)
                + "\n...[内容过长，已截断 " + (text.length() - headLen - tailLen) + " 字符]...\n"
                + text.substring(text.length() - tailLen);
    }

    /**
     * 工具执行后异步摘要（在 resolveToolCalls 阶段调用，不阻塞流式）。
     * 3s 超时降级为智能截断。
     */
    private String summarizeToolResultAsync(String toolResult) {
        if (toolResult == null || toolResult.length() <= 2000) return toolResult;
        try {
            String input = toolResult.length() > 4000 ? toolResult.substring(0, 4000) : toolResult;
            String prompt = "请将以下工具返回的数据精简为约 500 字的摘要，保留关键信息：\n\n" + input;
            String summary = CompletableFuture.supplyAsync(() -> fastChatModel.chat(prompt), llmExecutor)
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
            if (summary != null && !summary.isBlank()) {
                return "[工具结果摘要]\n" + summary;
            }
        } catch (Exception e) {
            log.debug("Tool result summarization failed, using smart truncate: {}", e.getMessage());
        }
        return smartTruncate(toolResult, 2000);
    }

    /**
     * 注入意图分析到系统提示，让 AI 知道分类结果后回答更精准
     */
    private void injectIntentContext(List<dev.langchain4j.data.message.ChatMessage> messages,
                                      IntentClassification intentResult) {
        if (messages.isEmpty()) return;
        SystemMessage original = (SystemMessage) messages.get(0);
        String intentInfo = "\n\n--- 意图分析 ---\n" +
                "用户意图: " + intentResult.getPrimaryIntent().name() + "\n" +
                "子意图: " + intentResult.getSubIntent().name() + "\n" +
                "置信度: " + (int)(intentResult.getConfidence() * 100) + "%\n" +
                "分类器: " + intentResult.getClassifiedBy().name() + "\n" +
                "槽位: " + intentResult.getSlots() + "\n" +
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
