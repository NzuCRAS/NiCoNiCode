package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.ChatReq;
import com.niconicode.agent.chat.dto.ChatResp;
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
    private final ChatTraceService traceService;

    @Value("${ai.tools.enabled:true}")
    private boolean toolsEnabled;

    private final ConcurrentHashMap<Long, SseEmitter> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, StringBuilder> activeStreamContents = new ConcurrentHashMap<>();

    private static final int MAX_TOOL_ITERATIONS = 5;

    private static final String SYSTEM_PROMPT = """
            你是 NiCoNiCode 的 AI 技术助手，专注于编程和技术领域的问答。
            你的特点：
            1. 精通主流编程语言和框架
            2. 能够搜索知识库获取准确的技术文档和教程
            3. 能够查询技术追踪系统了解最新版本和更新动态
            4. 回答准确、条理清晰，善用代码示例
            注意事项：
            - 使用中文回答
            - 代码使用 Markdown 格式
            - 不确定的内容要如实说明
            - 当用户询问特定技术的版本、更新、文档时，优先使用工具获取准确信息
            - 简单的问候或通用问题无需使用工具，直接回答即可
            - 使用工具搜索到的内容要注明来源
            """;

    public ChatResp processMessage(Long userId, ChatReq req) {
        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());
        boolean isFirstMessage = isFirstMessage(session.getId());

        List<dev.langchain4j.data.message.ChatMessage> messages = buildMessages(session, req.getMessage());

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
        }

        memoryService.saveMessage(session.getId(), "USER", req.getMessage());
        memoryService.saveMessage(session.getId(), "ASSISTANT", reply);

        String sessionTitle = session.getTitle();
        if (isFirstMessage && "新对话".equals(sessionTitle)) {
            sessionTitle = generateSessionTitle(req.getMessage(), reply);
            memoryService.updateSessionTitle(session.getId(), sessionTitle);
        }

        return ChatResp.builder()
                .sessionId(session.getId())
                .reply(reply)
                .sessionTitle(sessionTitle)
                .build();
    }

    public SseEmitter processMessageStream(Long userId, ChatReq req) {
        SseEmitter emitter = new SseEmitter(120_000L);

        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());
        List<dev.langchain4j.data.message.ChatMessage> messages = buildMessages(session, req.getMessage());

        memoryService.saveMessage(session.getId(), "USER", req.getMessage());

        StringBuilder fullReply = new StringBuilder();

        activeStreams.put(session.getId(), emitter);
        activeStreamContents.put(session.getId(), fullReply);

        Runnable cleanup = () -> {
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
                    // 意图识别：简单闲聊直接跳过工具调用
                    boolean skipTools = intentClassifier.isSimpleGreeting(req.getMessage());
                    if (!skipTools) {
                        long intentStart = System.currentTimeMillis();
                        IntentClassifier.IntentResult intentResult =
                                intentClassifier.classify(req.getMessage(), session.getSummary());
                        traceService.trace(session.getId(), null, ChatTraceService.STAGE_INTENT,
                                req.getMessage(), intentResult.getIntent().name(),
                                (int)(System.currentTimeMillis() - intentStart));
                        skipTools = intentResult.getIntent() == IntentClassifier.Intent.GENERAL_CHAT;

                        // 问题重写（指代消解）
                        if (!skipTools) {
                            long rewriteStart = System.currentTimeMillis();
                            String context = buildConversationContext(session);
                            QueryRewriter.RewriteResult rewrite = queryRewriter.rewrite(req.getMessage(), context);
                            traceService.trace(session.getId(), null, ChatTraceService.STAGE_REWRITE,
                                    req.getMessage(), rewrite.getRewritten(),
                                    (int)(System.currentTimeMillis() - rewriteStart));
                            // 如果重写后的查询与原始不同，替换最后一条 UserMessage
                            if (!rewrite.getRewritten().equals(req.getMessage())) {
                                messages.set(messages.size() - 1,
                                        UserMessage.from(rewrite.getRewritten()));
                            }
                        }
                    }

                    if (skipTools) {
                        // 闲聊模式：跳过工具，直接流式
                        startStreaming(messages, emitter, session, fullReply);
                    } else {
                        // Phase 1: 同步工具解析
                        List<dev.langchain4j.data.message.ChatMessage> resolvedMessages =
                                resolveToolCalls(new ArrayList<>(messages), emitter);
                        // Phase 2: 流式输出最终回答
                        startStreaming(resolvedMessages, emitter, session, fullReply);
                    }
                } catch (Exception e) {
                    log.error("Tool resolution or streaming failed", e);
                    try {
                        if (!fullReply.isEmpty()) {
                            memoryService.saveMessage(session.getId(), "ASSISTANT", fullReply.toString());
                        }
                        emitter.send(SseEmitter.event().name("error").data("AI 服务异常"));
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                }
            });
        } else {
            // 降级路径：无条件 RAG + 直接流式
            String ragContext = ragService.retrieveContext(req.getMessage());
            if (ragContext != null && !ragContext.isBlank()) {
                injectRagContext(messages, ragContext);
            }
            startStreaming(messages, emitter, session, fullReply);
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
            List<dev.langchain4j.data.message.ChatMessage> messages, SseEmitter emitter) {
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
                String result = toolService.executeTool(req);
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
        }
        return messages;
    }

    private void sendToolEvent(SseEmitter emitter, List<String> toolNames) {
        try {
            String toolNamesJson = toolNames.stream()
                    .map(n -> "\"" + n + "\"")
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
                                SseEmitter emitter, ChatSession session, StringBuilder fullReply) {
        streamingModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
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
                } catch (Exception e) {
                    log.warn("SSE send failed", e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                String finalReply = fullReply.toString();
                memoryService.saveMessage(session.getId(), "ASSISTANT", finalReply);
                // 异步提取关键词
                extractAndRecordMentions(session.getTitle(), finalReply);
                // 异步摘要压缩
                CompletableFuture.runAsync(() -> {
                    try {
                        memoryService.compressMemoryIfNeeded(session.getId(), chatModel);
                    } catch (Exception e) {
                        log.warn("Memory compression failed for session {}", session.getId(), e);
                    }
                });
                try {
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data("{\"sessionId\":" + session.getId() + "}"));
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("SSE complete failed", e);
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("Streaming error", error);
                if (!fullReply.isEmpty()) {
                    memoryService.saveMessage(session.getId(), "ASSISTANT", fullReply.toString());
                }
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("AI 服务异常"));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }
        });
    }

    // ---- Message Building ----

    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            ChatSession session, String userMessage) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        StringBuilder systemContent = new StringBuilder(SYSTEM_PROMPT);
        if (session.getSummary() != null) {
            systemContent.append("\n\n之前对话的摘要：\n").append(session.getSummary());
        }
        messages.add(SystemMessage.from(systemContent.toString()));

        List<ChatMessage> history = memoryService.getRecentMessages(session.getId());
        for (ChatMessage msg : history) {
            if ("USER".equals(msg.getRole())) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if ("ASSISTANT".equals(msg.getRole())) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }

        messages.add(UserMessage.from(userMessage));
        return messages;
    }

    private void injectRagContext(List<dev.langchain4j.data.message.ChatMessage> messages, String ragContext) {
        if (messages.isEmpty()) return;
        SystemMessage original = (SystemMessage) messages.get(0);
        messages.set(0, SystemMessage.from(
                original.text() + "\n\n以下是从知识库检索到的相关参考资料：\n" + ragContext));
    }

    private boolean isFirstMessage(Long sessionId) {
        List<ChatMessage> messages = memoryService.getSessionMessagesDirectly(sessionId);
        return messages == null || messages.isEmpty();
    }

    /**
     * 构建简短的对话上下文文本（用于意图识别和问题重写）
     */
    private String buildConversationContext(ChatSession session) {
        StringBuilder context = new StringBuilder();
        if (session.getSummary() != null && !session.getSummary().isBlank()) {
            context.append("摘要: ").append(session.getSummary()).append("\n");
        }
        List<ChatMessage> recent = memoryService.getRecentMessages(session.getId());
        int start = Math.max(0, recent.size() - 6); // 最近 3 轮对话
        for (int i = start; i < recent.size(); i++) {
            ChatMessage msg = recent.get(i);
            context.append(msg.getRole()).append(": ").append(
                    msg.getContent().length() > 200 ? msg.getContent().substring(0, 200) : msg.getContent()
            ).append("\n");
        }
        return context.toString();
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
    private void extractAndRecordMentions(String sessionTitle, String aiReply) {
        CompletableFuture.runAsync(() -> {
            try {
                List<String> techNames = trackerService.getAllTechNames();
                String context = (sessionTitle != null ? sessionTitle : "") + " " + aiReply;
                String contextLower = context.toLowerCase();
                for (String name : techNames) {
                    if (contextLower.contains(name.toLowerCase())) {
                        trackerService.recordMention(name);
                        log.debug("Recorded mention for tracked tech: {}", name);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to extract tech mentions", e);
            }
        });
    }
}
