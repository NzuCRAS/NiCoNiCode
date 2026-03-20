package com.niconicode.agent.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 三级意图识别结构化输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentClassification {

    // ---- 主意图枚举 ----

    public enum Intent {
        TECH_QUERY, VERSION_UPDATE, COMPARISON, CODE_HELP,
        GITHUB_ANALYSIS, REPORT_QUERY, GENERAL_CHAT, UNCLEAR
    }

    // ---- 子意图枚举 ----

    public enum SubIntent {
        // TECH_QUERY
        CONCEPT, CONFIGURATION, FEATURE, TROUBLESHOOT,
        // VERSION_UPDATE
        LATEST_VERSION, CHANGELOG, RELEASE_NOTE,
        // COMPARISON
        HEAD_TO_HEAD, MIGRATION, SELECTION,
        // CODE_HELP
        WRITE_CODE, EXPLAIN_CODE, DEBUG, BEST_PRACTICE,
        // GITHUB_ANALYSIS
        REPO_OVERVIEW, REPO_STATS, CONTRIBUTOR,
        // REPORT_QUERY
        LATEST_NEWS, TECH_NEWS, TRENDING,
        // GENERAL_CHAT
        GREETING, FAREWELL, THANKS, CONFIRMATION, ABOUT_BOT,
        // fallback
        UNKNOWN
    }

    // ---- 分类器来源 ----

    public enum ClassifiedBy {
        RULE, VECTOR, LLM, FALLBACK
    }

    // ---- 字段 ----

    @Builder.Default
    private Intent primaryIntent = Intent.GENERAL_CHAT;

    @Builder.Default
    private SubIntent subIntent = SubIntent.UNKNOWN;

    @Builder.Default
    private double confidence = 0.5;

    @Builder.Default
    private List<AlternativeIntent> alternativeIntents = new ArrayList<>();

    @Builder.Default
    private Map<String, String> slots = new HashMap<>();

    private String clarification;

    @Builder.Default
    private ClassifiedBy classifiedBy = ClassifiedBy.FALLBACK;

    private long latencyMs;

    // ---- 备选意图 ----

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlternativeIntent {
        private Intent intent;
        private SubIntent subIntent;
        private double confidence;
    }

    // ---- 便捷工厂方法 ----

    public static IntentClassification fallback() {
        return IntentClassification.builder()
                .primaryIntent(Intent.GENERAL_CHAT)
                .subIntent(SubIntent.UNKNOWN)
                .confidence(0.3)
                .classifiedBy(ClassifiedBy.FALLBACK)
                .build();
    }

    /**
     * 判断该意图是否需要工具解析
     */
    public boolean needsToolResolution() {
        return primaryIntent != Intent.GENERAL_CHAT && primaryIntent != Intent.UNCLEAR;
    }
}
