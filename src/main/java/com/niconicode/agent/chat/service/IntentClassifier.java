package com.niconicode.agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.chat.dto.IntentClassification;
import com.niconicode.agent.chat.dto.IntentClassification.ClassifiedBy;
import com.niconicode.agent.chat.dto.IntentClassification.Intent;
import com.niconicode.agent.chat.dto.IntentClassification.SubIntent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

import com.niconicode.common.util.SafeTemplates;

/**
 * 三级级联意图分类器：L1 规则引擎 → L2 向量相似度 → L3 LLM 兜底
 */
@Slf4j
@Service
public class IntentClassifier {

    private final ChatLanguageModel chatModel;
    private final RuleBasedClassifier ruleClassifier;
    private final VectorIntentClassifier vectorClassifier;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final double L1_DIRECT_THRESHOLD = 0.85;
    private static final double L2_DIRECT_THRESHOLD = 0.80;
    private static final double BOOST_THRESHOLD = 0.75;
    private static final double BOOST_AMOUNT = 0.10;

    public IntentClassifier(@Qualifier("fastChatModel") ChatLanguageModel chatModel,
                            RuleBasedClassifier ruleClassifier,
                            VectorIntentClassifier vectorClassifier) {
        this.chatModel = chatModel;
        this.ruleClassifier = ruleClassifier;
        this.vectorClassifier = vectorClassifier;
    }

    // ---- 噪音过滤（L0） ----

    public enum RouteDecision {
        NOISE_SKIP,
        NEEDS_CLASSIFICATION
    }

    @Data
    public static class RouteResult {
        private RouteDecision decision = RouteDecision.NEEDS_CLASSIFICATION;
    }

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

    // ---- 新版三级级联分类 ----

    /**
     * 三级级联意图分类
     */
    public IntentClassification classify(String userMessage, String conversationContext) {
        long start = System.currentTimeMillis();

        // L1 规则引擎 (<1ms)
        IntentClassification l1 = ruleClassifier.classify(userMessage);
        if (l1 != null && l1.getConfidence() >= L1_DIRECT_THRESHOLD) {
            l1.setLatencyMs(System.currentTimeMillis() - start);
            log.debug("L1 rule hit: {} / {} (conf={}) for: {}",
                    l1.getPrimaryIntent(), l1.getSubIntent(), l1.getConfidence(), userMessage);
            return l1;
        }

        // L2 向量相似度 (<35ms)
        IntentClassification l2 = vectorClassifier.classify(userMessage);
        if (l2 != null && l2.getConfidence() >= L2_DIRECT_THRESHOLD) {
            // 合并 L1 的槽位（如果有）
            if (l1 != null && l1.getSlots() != null) {
                l1.getSlots().forEach((k, v) -> l2.getSlots().putIfAbsent(k, v));
            }
            l2.setLatencyMs(System.currentTimeMillis() - start);
            log.debug("L2 vector hit: {} / {} (conf={}) for: {}",
                    l2.getPrimaryIntent(), l2.getSubIntent(), l2.getConfidence(), userMessage);
            return l2;
        }

        // L1+L2 一致性增信
        if (l1 != null && l2 != null && l1.getPrimaryIntent() == l2.getPrimaryIntent()) {
            double boosted = Math.max(l1.getConfidence(), l2.getConfidence()) + BOOST_AMOUNT;
            if (boosted >= BOOST_THRESHOLD) {
                IntentClassification merged = IntentClassification.builder()
                        .primaryIntent(l1.getPrimaryIntent())
                        .subIntent(l2.getSubIntent() != SubIntent.UNKNOWN ? l2.getSubIntent() : l1.getSubIntent())
                        .confidence(Math.min(boosted, 1.0))
                        .slots(mergeSlots(l1.getSlots(), l2.getSlots()))
                        .classifiedBy(ClassifiedBy.VECTOR) // L1+L2 一致，标记为 VECTOR
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();
                log.debug("L1+L2 boost: {} / {} (conf={}) for: {}",
                        merged.getPrimaryIntent(), merged.getSubIntent(), merged.getConfidence(), userMessage);
                return merged;
            }
        }

        // L3 LLM 兜底 (200-500ms)
        IntentClassification l3 = classifyWithLLM(userMessage, conversationContext, l1, l2);
        l3.setLatencyMs(System.currentTimeMillis() - start);
        log.debug("L3 LLM: {} / {} (conf={}) for: {}",
                l3.getPrimaryIntent(), l3.getSubIntent(), l3.getConfidence(), userMessage);
        return l3;
    }

    // ---- L3 LLM 分类 ----

    private IntentClassification classifyWithLLM(String userMessage, String conversationContext,
                                                  IntentClassification l1Hint, IntentClassification l2Hint) {
        // 构建 hint 信息
        StringBuilder hintInfo = new StringBuilder();
        if (l1Hint != null) {
            hintInfo.append("规则引擎预判: ").append(l1Hint.getPrimaryIntent())
                    .append("/").append(l1Hint.getSubIntent())
                    .append(" (conf=").append(l1Hint.getConfidence()).append(")\n");
        }
        if (l2Hint != null) {
            hintInfo.append("向量相似度预判: ").append(l2Hint.getPrimaryIntent())
                    .append("/").append(l2Hint.getSubIntent())
                    .append(" (conf=").append(l2Hint.getConfidence()).append(")\n");
        }

        String prompt = """
            当前日期: """ + java.time.LocalDate.now().toString() + """
                    结合对话上下文理解用户的真实意图，对以下用户消息进行意图分类。
                    返回严格的 JSON 格式（不要包含其他文字）:
                    {"intent": "主意图", "subIntent": "子意图", "confidence": 0.0-1.0, "slots": {}, "alternativeIntent": "备选意图", "clarification": null}

                    重要：
                    - 必须结合对话上下文理解用户消息的真正含义
                    - 如 "ping" 在无上下文时是连通性测试(GENERAL_CHAT)，在技术讨论中可能指网络 ping 命令(TECH_QUERY)
                    - "请讲讲第一个" 需要看上下文中提到了什么列表
                    - "这个怎么配置" 需要看上下文在讨论什么技术
                    - 如果置信度 < 0.5，请在 clarification 中填写一个简短的澄清问题

                    主意图类型:
                    - TECH_QUERY: 技术知识查询
                    - VERSION_UPDATE: 版本/更新查询
                    - COMPARISON: 技术对比
                    - CODE_HELP: 代码/实践帮助
                    - GITHUB_ANALYSIS: GitHub 仓库分析
                    - REPORT_QUERY: 报道查询
                    - GENERAL_CHAT: 闲聊/问候/确认/感谢
                    - UNCLEAR: 意图不明确（clarification 必填）

                    子意图类型:
                    TECH_QUERY → CONCEPT/CONFIGURATION/FEATURE/TROUBLESHOOT
                    VERSION_UPDATE → LATEST_VERSION/CHANGELOG/RELEASE_NOTE
                    COMPARISON → HEAD_TO_HEAD/MIGRATION/SELECTION
                    CODE_HELP → WRITE_CODE/EXPLAIN_CODE/DEBUG/BEST_PRACTICE
                    GITHUB_ANALYSIS → REPO_OVERVIEW/REPO_STATS/CONTRIBUTOR
                    REPORT_QUERY → LATEST_NEWS/TECH_NEWS/TRENDING
                    GENERAL_CHAT → GREETING/FAREWELL/THANKS/CONFIRMATION/ABOUT_BOT

                    slots 字段说明（按主意图填充）:
                    - TECH_QUERY: {"techName": "...", "framework": "...", "version": "..."}
                    - VERSION_UPDATE: {"techName": "...", "versionRange": "...", "timeRange": "..."}
                    - COMPARISON: {"techA": "...", "techB": "...", "dimension": "..."}
                    - CODE_HELP: {"language": "...", "framework": "...", "task": "..."}
                    - GITHUB_ANALYSIS: {"repoUrl": "...", "owner": "...", "repoName": "..."}
                    - REPORT_QUERY: {"techName": "...", "timeRange": "..."}

                    """ + (hintInfo.isEmpty() ? "" : "前级分类器参考（仅供参考，结合上下文自主判断）:\n" + hintInfo) + """
                    对话上下文: """ + SafeTemplates.s(conversationContext != null ? conversationContext : "无") + """
                    用户消息: """ + SafeTemplates.s(userMessage);

        try {
            String response = chatModel.chat(prompt).trim();
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                response = response.substring(jsonStart, jsonEnd + 1);
            }

            JsonNode json = objectMapper.readTree(response);

            Intent intent = parseIntent(json, "intent", Intent.GENERAL_CHAT);
            SubIntent subIntent = parseSubIntent(json, "subIntent", SubIntent.UNKNOWN);
            double confidence = json.has("confidence") ? json.get("confidence").asDouble() : 0.5;

            // 解析 slots
            Map<String, String> slots = new HashMap<>();
            if (json.has("slots") && json.get("slots").isObject()) {
                json.get("slots").fields().forEachRemaining(e -> {
                    if (!e.getValue().isNull()) {
                        slots.put(e.getKey(), e.getValue().asText());
                    }
                });
            }

            // 合并 L1/L2 的 slots
            if (l1Hint != null && l1Hint.getSlots() != null) {
                l1Hint.getSlots().forEach(slots::putIfAbsent);
            }
            if (l2Hint != null && l2Hint.getSlots() != null) {
                l2Hint.getSlots().forEach(slots::putIfAbsent);
            }

            String clarification = null;
            if (json.has("clarification") && !json.get("clarification").isNull()) {
                clarification = json.get("clarification").asText();
            }

            // 备选意图
            List<IntentClassification.AlternativeIntent> alternatives = new ArrayList<>();
            if (json.has("alternativeIntent") && !json.get("alternativeIntent").isNull()) {
                try {
                    Intent altIntent = Intent.valueOf(json.get("alternativeIntent").asText());
                    alternatives.add(IntentClassification.AlternativeIntent.builder()
                            .intent(altIntent)
                            .subIntent(SubIntent.UNKNOWN)
                            .confidence(confidence * 0.7)
                            .build());
                } catch (Exception ignored) {}
            }

            return IntentClassification.builder()
                    .primaryIntent(intent)
                    .subIntent(subIntent)
                    .confidence(confidence)
                    .slots(slots)
                    .clarification(clarification)
                    .alternativeIntents(alternatives)
                    .classifiedBy(ClassifiedBy.LLM)
                    .build();
        } catch (Exception e) {
            log.warn("L3 LLM classification failed for: {}", userMessage, e);
            // 回退到 L1/L2 最佳结果
            if (l2Hint != null && l2Hint.getConfidence() > 0.5) {
                l2Hint.setClassifiedBy(ClassifiedBy.FALLBACK);
                return l2Hint;
            }
            if (l1Hint != null && l1Hint.getConfidence() > 0.5) {
                l1Hint.setClassifiedBy(ClassifiedBy.FALLBACK);
                return l1Hint;
            }
            return IntentClassification.fallback();
        }
    }

    private Intent parseIntent(JsonNode json, String field, Intent defaultVal) {
        if (!json.has(field)) return defaultVal;
        try {
            return Intent.valueOf(json.get(field).asText());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private SubIntent parseSubIntent(JsonNode json, String field, SubIntent defaultVal) {
        if (!json.has(field)) return defaultVal;
        try {
            return SubIntent.valueOf(json.get(field).asText());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private Map<String, String> mergeSlots(Map<String, String> a, Map<String, String> b) {
        Map<String, String> merged = new HashMap<>();
        if (a != null) merged.putAll(a);
        if (b != null) b.forEach(merged::putIfAbsent);
        return merged;
    }

    // ---- 兼容旧 API ----

    /**
     * 判断该意图是否需要工具解析（兼容旧代码引用）
     */
    public static boolean needsToolResolution(Intent intent) {
        return intent != Intent.GENERAL_CHAT && intent != Intent.UNCLEAR;
    }

    /**
     * @deprecated 使用 IntentClassification.Intent 代替
     */
    @Deprecated
    public enum OldIntent {
        TECH_QUERY, VERSION_UPDATE, COMPARISON, CODE_HELP,
        GITHUB_ANALYSIS, REPORT_QUERY, GENERAL_CHAT, UNCLEAR
    }

    /**
     * @deprecated 使用 IntentClassification 代替
     */
    @Deprecated
    @Data
    public static class IntentResult {
        private Intent intent = Intent.GENERAL_CHAT;
        private double confidence = 0.5;
        private List<String> entities = new ArrayList<>();
        private String clarification;
    }

    /**
     * @deprecated 已被新管道取代
     */
    @Deprecated
    public boolean isSimpleGreeting(String message) {
        return route(message).getDecision() == RouteDecision.NOISE_SKIP;
    }
}
