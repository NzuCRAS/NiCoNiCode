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
        // 最近 12 条消息（6 轮对话），截断 400 字；并且识别“列表/枚举输出”，辅助“第几个/第二篇”指代消解
        int start = Math.max(0, recentMessages.size() - 12);
        for (int i = start; i < recentMessages.size(); i++) {
            ChatMessage msg = recentMessages.get(i);
            String role = getRoleSafe(msg);
            String content = getContentSafe(msg);

            if ("ASSISTANT".equals(role) && looksLikeListOutput(content)) {
                context.append("ASSISTANT(列表输出): ");
            } else {
                context.append(role).append(": ");
            }

            context.append(content.length() > 400 ? content.substring(0, 400) : content)
                    .append("\n");
        }
        // 上次意图实体
        List<String> entities = getEntitiesSafe(lastIntentResult);
        if (entities != null && !entities.isEmpty()) {
            context.append("上次意图实体: ").append(entities).append("\n");
        }
        return context.toString();
    }

    private static String getRoleSafe(ChatMessage msg) {
        if (msg == null) return "";
        try {
            // 大多数情况下 ChatMessage 有 getter
            return (String) ChatMessage.class.getMethod("getRole").invoke(msg);
        } catch (Exception ignore) {
            // 兼容字段可见（不同代码生成策略/老版本 class）
            try {
                java.lang.reflect.Field f = ChatMessage.class.getDeclaredField("role");
                f.setAccessible(true);
                Object v = f.get(msg);
                return v != null ? v.toString() : "";
            } catch (Exception e) {
                return "";
            }
        }
    }

    private static String getContentSafe(ChatMessage msg) {
        if (msg == null) return "";
        try {
            return (String) ChatMessage.class.getMethod("getContent").invoke(msg);
        } catch (Exception ignore) {
            try {
                java.lang.reflect.Field f = ChatMessage.class.getDeclaredField("content");
                f.setAccessible(true);
                Object v = f.get(msg);
                return v != null ? v.toString() : "";
            } catch (Exception e) {
                return "";
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> getEntitiesSafe(IntentClassifier.IntentResult intentResult) {
        if (intentResult == null) return null;
        try {
            return (List<String>) IntentClassifier.IntentResult.class.getMethod("getEntities").invoke(intentResult);
        } catch (Exception ignore) {
            try {
                java.lang.reflect.Field f = IntentClassifier.IntentResult.class.getDeclaredField("entities");
                f.setAccessible(true);
                Object v = f.get(intentResult);
                return (List<String>) v;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static boolean looksLikeListOutput(String content) {
        if (content == null) return false;
        // 简单启发：包含多行、且出现 1./2./3. 或 - / • 等列表符号，或多次出现“【标题】”样式
        String c = content.trim();
        if (c.isEmpty()) return false;
        int lines = c.split("\\r?\\n").length;
        if (lines < 3) return false;
        int hits = 0;
        if (c.matches("(?s).*\\n\\s*[-•*]\\s+.*")) hits++;
        if (c.matches("(?s).*\\n\\s*(?:[1-9]|10)[\\.|、)]\\s+.*")) hits++;
        // 报道列表常见形态：多次出现【】
        int bracket = 0;
        for (int i = 0; i < c.length(); i++) {
            if (c.charAt(i) == '【') bracket++;
        }
        if (bracket >= 2) hits++;
        return hits >= 1;
    }
}
