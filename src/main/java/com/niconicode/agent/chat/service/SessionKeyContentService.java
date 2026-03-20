package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.chat.service.TraceLogger.TraceContext;
import com.niconicode.conversation.entity.SessionKeyContent;
import com.niconicode.conversation.mapper.SessionKeyContentMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SessionKeyContentService {

    private final SessionKeyContentMapper keyContentMapper;
    private final ChatLanguageModel fastChatModel;
    private final TraceLogger traceLogger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionKeyContentService(SessionKeyContentMapper keyContentMapper,
                                    @Qualifier("fastChatModel") ChatLanguageModel fastChatModel,
                                    TraceLogger traceLogger) {
        this.keyContentMapper = keyContentMapper;
        this.fastChatModel = fastChatModel;
        this.traceLogger = traceLogger;
    }

    /**
     * 从单轮对话中提取关键内容
     */
    public void extractKeyContent(Long sessionId, String userMessage, String aiReply,
                                  Long sourceMessageId, TraceContext traceCtx) {
        if (userMessage == null || userMessage.isBlank()) return;

        String dialog = "用户: " + truncate(userMessage, 1000)
                + "\n助手: " + truncate(aiReply != null ? aiReply : "", 2000);

        String prompt = """
                从以下对话中提取关键内容，仅返回JSON，不要多余文字。
                1. ENTITY: 具体技术/框架/工具名称（如 Spring Boot 3.3, Redis 等）
                2. DECISION: 用户做出的技术决策或结论（如"选择MyBatis-Plus而非JPA"）
                3. CODE_SNIPPET: 重要代码片段（保留代码，标注语言和用途，≤500字）

                如果没有值得提取的内容，返回: {"items": []}
                返回格式: {"items": [{"type": "ENTITY", "content": "Spring Boot 3.3"}, {"type": "DECISION", "content": "选择Redis做缓存"}]}

                对话：
                """ + dialog;

        try {
            long start = System.currentTimeMillis();
            String response = fastChatModel.chat(prompt);
            int duration = (int) (System.currentTimeMillis() - start);

            String json = extractJson(response);
            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.get("items");

            if (items == null || !items.isArray()) return;

            int saved = 0;
            for (JsonNode item : items) {
                String type = item.has("type") ? item.get("type").asText() : null;
                String content = item.has("content") ? item.get("content").asText() : null;

                if (type == null || content == null || content.isBlank()) continue;
                if (!Set.of("ENTITY", "DECISION", "CODE_SNIPPET").contains(type)) continue;

                SessionKeyContent kc = new SessionKeyContent();
                kc.setSessionId(sessionId);
                kc.setContentType(type);
                kc.setContent(content);
                kc.setSourceMessageId(sourceMessageId);
                keyContentMapper.insert(kc);
                saved++;
            }

            if (traceCtx != null) {
                traceLogger.trace(traceCtx, "KEY_CONTENT_EXTRACT",
                        "saved=" + saved + ", duration=" + duration + "ms");
            }
        } catch (Exception e) {
            log.warn("Key content extraction failed for session {}: {}", sessionId, e.getMessage());
            if (traceCtx != null) {
                traceLogger.traceError(traceCtx, "KEY_CONTENT_EXTRACT", e);
            }
        }
    }

    /**
     * 获取会话的关键内容（最近20条）
     */
    public List<SessionKeyContent> getSessionKeyContents(Long sessionId) {
        return keyContentMapper.selectList(
                new LambdaQueryWrapper<SessionKeyContent>()
                        .eq(SessionKeyContent::getSessionId, sessionId)
                        .orderByDesc(SessionKeyContent::getCreatedAt)
                        .last("LIMIT 20"));
    }

    /**
     * 格式化关键内容为注入文本
     */
    public static String formatForContext(List<SessionKeyContent> contents) {
        if (contents == null || contents.isEmpty()) return "";

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (SessionKeyContent kc : contents) {
            grouped.computeIfAbsent(kc.getContentType(), k -> new ArrayList<>())
                    .add(kc.getContent());
        }

        StringBuilder sb = new StringBuilder("--- 本次会话要点 ---\n");
        if (grouped.containsKey("ENTITY")) {
            sb.append("技术实体: ").append(String.join(", ", grouped.get("ENTITY"))).append("\n");
        }
        if (grouped.containsKey("DECISION")) {
            sb.append("技术决策: ").append(String.join("; ", grouped.get("DECISION"))).append("\n");
        }
        if (grouped.containsKey("CODE_SNIPPET")) {
            sb.append("代码片段: [").append(grouped.get("CODE_SNIPPET").size()).append("个片段已记录]\n");
        }
        sb.append("--- 会话要点结束 ---");
        return sb.toString();
    }

    /**
     * 会话删除时清理关键内容
     */
    public void deleteBySessionId(Long sessionId) {
        keyContentMapper.delete(
                new LambdaQueryWrapper<SessionKeyContent>()
                        .eq(SessionKeyContent::getSessionId, sessionId));
    }

    // ---- 工具方法 ----

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static String extractJson(String response) {
        if (response == null) return "{}";
        int start = response.indexOf("```json");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        start = response.indexOf("```");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }
        return response.trim();
    }
}
