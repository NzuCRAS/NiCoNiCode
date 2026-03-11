package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.ChatReq;
import com.niconicode.agent.chat.dto.ChatResp;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingModel;
    private final MemoryService memoryService;
    private final RagService ragService;

    private static final String SYSTEM_PROMPT = """
            你是 NiCoNiCode 的 AI 技术助手，专注于编程和技术领域的问答。

            你的特点：
            1. 精通主流编程语言和框架（Java、Python、JavaScript、Spring、Vue等）
            2. 能够分析 GitHub 仓库、Issue、PR
            3. 了解最新的技术动态和版本更新
            4. 回答准确、条理清晰，善用代码示例

            注意事项：
            - 使用中文回答
            - 代码使用 Markdown 格式
            - 不确定的内容要如实说明
            - 参考知识库内容时注明来源
            """;

    public ChatResp processMessage(Long userId, ChatReq req) {
        // 1. 获取或创建会话
        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());

        // 2. RAG 检索
        String ragContext = ragService.retrieveContext(req.getMessage());

        // 3. 构建消息列表
        List<dev.langchain4j.data.message.ChatMessage> messages = buildMessages(session, ragContext, req.getMessage());

        // 4. 调用 AI
        String reply;
        try {
            ChatResponse response = chatModel.chat(messages);
            reply = response.aiMessage().text();
        } catch (Exception e) {
            log.error("AI chat failed", e);
            reply = "抱歉，AI 服务暂时不可用，请稍后重试。";
        }

        // 5. 保存消息
        memoryService.saveMessage(session.getId(), "USER", req.getMessage());
        memoryService.saveMessage(session.getId(), "ASSISTANT", reply);

        return ChatResp.builder()
                .sessionId(session.getId())
                .reply(reply)
                .sessionTitle(session.getTitle())
                .build();
    }

    public SseEmitter processMessageStream(Long userId, ChatReq req) {
        SseEmitter emitter = new SseEmitter(120_000L);

        ChatSession session = memoryService.getOrCreateSession(userId, req.getSessionId(), req.getMessage());
        String ragContext = ragService.retrieveContext(req.getMessage());
        List<dev.langchain4j.data.message.ChatMessage> messages = buildMessages(session, ragContext, req.getMessage());

        memoryService.saveMessage(session.getId(), "USER", req.getMessage());

        StringBuilder fullReply = new StringBuilder();

        streamingModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                fullReply.append(partialResponse);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(partialResponse));
                } catch (Exception e) {
                    log.warn("SSE send failed", e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                memoryService.saveMessage(session.getId(), "ASSISTANT", fullReply.toString());
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

        return emitter;
    }

    private List<dev.langchain4j.data.message.ChatMessage> buildMessages(
            ChatSession session, String ragContext, String userMessage) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // System prompt
        StringBuilder systemContent = new StringBuilder(SYSTEM_PROMPT);
        if (ragContext != null && !ragContext.isBlank()) {
            systemContent.append("\n\n以下是从知识库检索到的相关参考资料：\n").append(ragContext);
        }
        if (session.getSummary() != null) {
            systemContent.append("\n\n之前对话的摘要：\n").append(session.getSummary());
        }
        messages.add(SystemMessage.from(systemContent.toString()));

        // 历史消息
        List<ChatMessage> history = memoryService.getRecentMessages(session.getId());
        for (ChatMessage msg : history) {
            if ("USER".equals(msg.getRole())) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if ("ASSISTANT".equals(msg.getRole())) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }

        // 当前用户消息
        messages.add(UserMessage.from(userMessage));
        return messages;
    }
}
