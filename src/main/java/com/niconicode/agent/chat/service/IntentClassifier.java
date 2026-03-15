package com.niconicode.agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
public class IntentClassifier {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentClassifier(@Qualifier("fastChatModel") ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    // ---- 噪音过滤 ----

    public enum RouteDecision {
        NOISE_SKIP,           // 纯噪音 (空白/纯标点)
        NEEDS_CLASSIFICATION  // 需要 AI 意图识别
    }

    @Data
    public static class RouteResult {
        private RouteDecision decision = RouteDecision.NEEDS_CLASSIFICATION;
    }

    // 纯标点/空内容
    private static final Pattern NOISE_PATTERN = Pattern.compile(
        "^[\\p{Punct}\\s!！?？。.~…]+$"
    );

    public RouteResult route(String message) {
        String trimmed = message.trim();
        RouteResult result = new RouteResult();
        if (trimmed.isEmpty() || NOISE_PATTERN.matcher(trimmed).matches()) {
            result.setDecision(RouteDecision.NOISE_SKIP);
        } else {
            result.setDecision(RouteDecision.NEEDS_CLASSIFICATION);
        }
        return result;
    }

    /**
     * 判断该意图是否需要工具解析（GENERAL_CHAT 和 UNCLEAR 直接流式回答）
     */
    public static boolean needsToolResolution(Intent intent) {
        return intent != Intent.GENERAL_CHAT && intent != Intent.UNCLEAR;
    }

    public enum Intent {
        TECH_QUERY,         // 技术知识查询
        VERSION_UPDATE,     // 版本/更新查询
        COMPARISON,         // 技术对比
        CODE_HELP,          // 代码/实践帮助
        GITHUB_ANALYSIS,    // GitHub 仓库分析
        REPORT_QUERY,       // 报道查询
        GENERAL_CHAT,       // 闲聊/问候
        UNCLEAR             // 意图不明
    }

    @Data
    public static class IntentResult {
        private Intent intent = Intent.GENERAL_CHAT;
        private double confidence = 0.5;
        private List<String> entities = new ArrayList<>();
        private String clarification;
    }

    public IntentResult classify(String userMessage, String conversationContext) {
        String prompt = """
                当前日期: %s
                结合对话上下文理解用户的真实意图，对以下用户消息进行意图分类。
                返回严格的 JSON 格式（不要包含其他文字）:
                {"intent": "类型", "confidence": 0.0-1.0, "entities": ["实体1"], "clarification": null}

                重要：
                - 必须结合对话上下文理解用户消息的真正含义
                - 如 "ping" 在无上下文时是连通性测试(GENERAL_CHAT)，在技术讨论中可能指网络 ping 命令(TECH_QUERY)
                - "请讲讲第一个" 需要看上下文中提到了什么列表
                - "这个怎么配置" 需要看上下文在讨论什么技术
                - 如果置信度 < 0.5，请在 clarification 中填写一个简短的澄清问题

                可选意图类型:
                - TECH_QUERY: 技术知识查询（什么是X, X有什么特性, X怎么配置）
                - VERSION_UPDATE: 版本/更新查询（X最新版本, X有什么更新, X发布了什么）
                - COMPARISON: 技术对比（X和Y哪个好, X vs Y）
                - CODE_HELP: 代码/实践帮助（怎么用X做Y, 帮我写一个...）
                - GITHUB_ANALYSIS: GitHub 仓库分析（分析这个仓库, 看看这个项目）
                - REPORT_QUERY: 报道查询（今天有什么报道, 最近有什么技术新闻）
                - GENERAL_CHAT: 闲聊/问候/确认/感谢/简单探针（你好, 谢谢, 再见, ok, ping）
                - UNCLEAR: 意图不明确且无法从上下文推断（此时 clarification 必填澄清问题）

                对话上下文: %s
                用户消息: %s
                """.formatted(
                java.time.LocalDate.now().toString(),
                conversationContext != null ? conversationContext : "无",
                userMessage);

        try {
            String response = chatModel.chat(prompt).trim();
            // 提取 JSON 部分
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                response = response.substring(jsonStart, jsonEnd + 1);
            }

            JsonNode json = objectMapper.readTree(response);
            IntentResult result = new IntentResult();

            String intentStr = json.has("intent") ? json.get("intent").asText() : "GENERAL_CHAT";
            try {
                result.setIntent(Intent.valueOf(intentStr));
            } catch (IllegalArgumentException e) {
                result.setIntent(Intent.GENERAL_CHAT);
            }

            result.setConfidence(json.has("confidence") ? json.get("confidence").asDouble() : 0.5);

            if (json.has("entities") && json.get("entities").isArray()) {
                List<String> entities = new ArrayList<>();
                for (JsonNode entity : json.get("entities")) {
                    entities.add(entity.asText());
                }
                result.setEntities(entities);
            }

            if (json.has("clarification") && !json.get("clarification").isNull()) {
                result.setClarification(json.get("clarification").asText());
            }

            log.debug("Intent classified: {} (confidence: {}) for message: {}",
                    result.getIntent(), result.getConfidence(), userMessage);
            return result;
        } catch (Exception e) {
            log.warn("Intent classification failed for: {}", userMessage, e);
            IntentResult fallback = new IntentResult();
            fallback.setIntent(Intent.GENERAL_CHAT);
            fallback.setConfidence(0.3);
            return fallback;
        }
    }

    /**
     * @deprecated 已被新管道取代，所有非噪音消息都经过 AI 意图识别
     */
    @Deprecated
    public boolean isSimpleGreeting(String message) {
        return route(message).getDecision() == RouteDecision.NOISE_SKIP;
    }
}
