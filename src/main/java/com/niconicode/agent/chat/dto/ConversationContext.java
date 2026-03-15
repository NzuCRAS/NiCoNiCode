package com.niconicode.agent.chat.dto;

import com.niconicode.agent.chat.service.IntentClassifier;
import com.niconicode.conversation.entity.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化对话上下文 — 统一承载会话、用户、历史记忆等信息，
 * 避免 buildMessages / buildConversationContext 各自查一次 DB。
 */
@Data
@Builder
public class ConversationContext {

    // 会话信息
    private Long sessionId;
    private String sessionTitle;

    // 用户信息
    private Long userId;
    private String nickname;

    // 历史记忆
    private String summary;
    @Builder.Default
    private List<ChatMessage> recentMessages = new ArrayList<>();
    private long totalActiveMessageCount;

    // 意图元数据（意图识别后填充）
    private IntentClassifier.IntentResult lastIntentResult;

    /**
     * 构建 LangChain4j 消息列表，替代原 ChatService.buildMessages()
     */
    public List<dev.langchain4j.data.message.ChatMessage> toLangChainMessages(
            String systemPrompt, String currentUserMessage) {

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();

        // SystemMessage = 系统提示 + 摘要 + 用户信息
        StringBuilder systemContent = new StringBuilder(systemPrompt);
        if (summary != null && !summary.isBlank()) {
            systemContent.append("\n\n之前对话的摘要：\n").append(summary);
        }
        if (nickname != null && !nickname.isBlank()) {
            systemContent.append("\n\n当前用户: ").append(nickname);
        }
        messages.add(SystemMessage.from(systemContent.toString()));

        // 历史消息
        for (ChatMessage msg : recentMessages) {
            if ("USER".equals(msg.getRole())) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if ("ASSISTANT".equals(msg.getRole())) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }

        // 当前用户消息
        messages.add(UserMessage.from(currentUserMessage));
        return messages;
    }

    /**
     * 构建意图识别/问题重写用的上下文字符串，替代原 ChatService.buildConversationContext()
     */
    public String toIntentContextString() {
        StringBuilder context = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            context.append("摘要: ").append(summary).append("\n");
        }
        if (nickname != null && !nickname.isBlank()) {
            context.append("用户: ").append(nickname).append("\n");
        }
        // 最近 6 条消息（3 轮对话），截断 200 字
        int start = Math.max(0, recentMessages.size() - 6);
        for (int i = start; i < recentMessages.size(); i++) {
            ChatMessage msg = recentMessages.get(i);
            String content = msg.getContent();
            context.append(msg.getRole()).append(": ")
                    .append(content.length() > 200 ? content.substring(0, 200) : content)
                    .append("\n");
        }
        // 上次意图实体
        if (lastIntentResult != null && lastIntentResult.getEntities() != null
                && !lastIntentResult.getEntities().isEmpty()) {
            context.append("上次意图实体: ").append(lastIntentResult.getEntities()).append("\n");
        }
        return context.toString();
    }
}
