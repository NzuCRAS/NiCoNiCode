package com.niconicode.agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifier {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
                对以下用户消息进行意图分类。返回严格的 JSON 格式（不要包含其他文字）:
                {"intent": "类型", "confidence": 0.0-1.0, "entities": ["实体1"], "clarification": null}

                可选意图类型:
                - TECH_QUERY: 技术知识查询（什么是X, X有什么特性, X怎么配置）
                - VERSION_UPDATE: 版本/更新查询（X最新版本, X有什么更新, X发布了什么）
                - COMPARISON: 技术对比（X和Y哪个好, X vs Y）
                - CODE_HELP: 代码/实践帮助（怎么用X做Y, 帮我写一个...）
                - GITHUB_ANALYSIS: GitHub 仓库分析（分析这个仓库, 看看这个项目）
                - REPORT_QUERY: 报道查询（今天有什么报道, 最近有什么技术新闻）
                - GENERAL_CHAT: 闲聊/问候（你好, 谢谢, 再见）
                - UNCLEAR: 意图不明确（此时 clarification 填写澄清问题）

                对话上下文: %s
                用户消息: %s
                """.formatted(
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
     * 快速判断是否为简单闲聊（无需 AI 调用）
     */
    public boolean isSimpleGreeting(String message) {
        String lower = message.trim().toLowerCase();
        String[] greetings = {"你好", "hello", "hi", "嗨", "hey", "谢谢", "感谢",
                "再见", "拜拜", "bye", "好的", "ok", "哈哈", "嗯"};
        for (String g : greetings) {
            if (lower.equals(g) || lower.startsWith(g + "!") || lower.startsWith(g + "！")) {
                return true;
            }
        }
        return lower.length() <= 5 && !lower.matches(".*[a-zA-Z]{3,}.*");
    }
}
