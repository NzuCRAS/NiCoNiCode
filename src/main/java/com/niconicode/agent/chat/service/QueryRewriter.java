package com.niconicode.agent.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.chat.dto.IntentClassification;
import com.niconicode.agent.chat.dto.IntentClassification.ClassifiedBy;
import com.niconicode.agent.chat.dto.IntentClassification.Intent;
import com.niconicode.agent.chat.dto.IntentClassification.SubIntent;
import com.niconicode.agent.chat.dto.SubTask;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

import com.niconicode.common.util.SafeTemplates;

@Slf4j
@Service
public class QueryRewriter {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    // 指代词检测
    private static final Pattern ANAPHORA_PATTERN = Pattern.compile(
            "(?:它|它们|这个|那个|前面|上面|上述|该|此|这些|那些)");

    public QueryRewriter(@Qualifier("fastChatModel") ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    // ---- RewriteResult（增强） ----

    @Data
    public static class RewriteResult {
        private String rewritten;
        private List<String> subQueries = new ArrayList<>();
        private List<SubTask> subTasks = new ArrayList<>();
        private RewriteStrategy strategy = RewriteStrategy.LLM;
        private long latencyMs;

        public enum RewriteStrategy {
            RULE_PASSTHROUGH, RULE_TEMPLATE, LLM
        }
    }

    // ---- 向后兼容：无 IntentClassification 时走 LLM 重写 ----

    public RewriteResult rewrite(String query, String conversationContext) {
        return rewrite(query, conversationContext, null);
    }

    // ---- 主入口：两级级联重写 ----

    public RewriteResult rewrite(String query, String conversationContext,
                                 IntentClassification intentClassification) {
        long startTime = System.currentTimeMillis();

        // 极短查询无条件绕过
        if (query.length() <= 5) {
            return buildPassthrough(query, startTime);
        }

        // 短查询且无上下文时跳过
        if (query.length() <= 10 && (conversationContext == null || conversationContext.isBlank())) {
            return buildPassthrough(query, startTime);
        }

        // Fast Path: 规则化重写（<1ms）
        if (intentClassification != null && canFastPath(query, intentClassification)) {
            RewriteResult result = fastPathRewrite(query, intentClassification);
            result.setLatencyMs(System.currentTimeMillis() - startTime);
            log.debug("Fast path rewrite: '{}' -> '{}', strategy={}, latency={}ms",
                    query, result.getRewritten(), result.getStrategy(), result.getLatencyMs());
            return result;
        }

        // Slow Path: LLM 重写 + DAG 拆分
        RewriteResult result = llmRewriteWithDag(query, conversationContext, intentClassification);
        result.setLatencyMs(System.currentTimeMillis() - startTime);
        return result;
    }

    // ---- Fast Path ----

    private boolean canFastPath(String query, IntentClassification ic) {
        // 条件：L1 规则引擎分类 + 高置信度 + 无指代词 + 无复合问题标志
        if (ic.getClassifiedBy() != ClassifiedBy.RULE) return false;
        if (ic.getConfidence() < 0.85) return false;
        if (ANAPHORA_PATTERN.matcher(query).find()) return false;
        // 复合问题标志：含比较连接词或多个问号
        if (query.matches("(?s).*(和|与|跟|还是|对比|比较|VS|vs).*")) return false;
        if (query.chars().filter(c -> c == '？' || c == '?').count() > 1) return false;
        return true;
    }

    private RewriteResult fastPathRewrite(String query, IntentClassification ic) {
        RewriteResult result = new RewriteResult();
        Map<String, String> slots = ic.getSlots();
        String rewritten = buildTemplateQuery(query, ic.getPrimaryIntent(), ic.getSubIntent(), slots);

        result.setRewritten(rewritten);
        result.setSubQueries(List.of(rewritten));
        result.setStrategy(RewriteResult.RewriteStrategy.RULE_TEMPLATE);

        // 生成单个 SubTask
        SubTask task = SubTask.builder()
                .id("t1")
                .query(rewritten)
                .type(SubTask.SubTaskType.QUESTION)
                .intent(ic.getPrimaryIntent())
                .subIntent(ic.getSubIntent())
                .slots(slots != null ? new HashMap<>(slots) : new HashMap<>())
                .dependsOn(Collections.emptyList())
                .build();
        result.setSubTasks(List.of(task));
        return result;
    }

    private String buildTemplateQuery(String originalQuery, Intent intent, SubIntent subIntent,
                                      Map<String, String> slots) {
        if (slots == null || slots.isEmpty()) return originalQuery;

        String techName = slots.get("techName");
        if (techName == null) return originalQuery;

        return switch (subIntent) {
            case CONFIGURATION -> techName + " 配置方法";
            case CONCEPT -> techName + " 概念介绍";
            case FEATURE -> techName + " 特性功能";
            case TROUBLESHOOT -> techName + " 问题排查";
            case LATEST_VERSION -> techName + " 最新版本";
            case CHANGELOG -> techName + " 更新日志";
            case RELEASE_NOTE -> techName + " 发布说明";
            case WRITE_CODE -> techName + " 代码示例";
            case EXPLAIN_CODE -> techName + " 代码解释";
            case DEBUG -> techName + " 调试方法";
            case BEST_PRACTICE -> techName + " 最佳实践";
            case REPO_OVERVIEW -> techName + " 仓库概况";
            case REPO_STATS -> techName + " 仓库统计";
            default -> originalQuery;
        };
    }

    // ---- Slow Path: LLM 重写 + DAG ----

    private RewriteResult llmRewriteWithDag(String query, String conversationContext,
                                            IntentClassification intentClassification) {
        boolean hasTimeWords = query.matches(
                "(?s).*(今天|昨日|昨天|前天|最近|近\\d+天|本周|这周|上周|本月|这个月|上个月|\\d{4}-\\d{2}-\\d{2}).*");

        String intentHint = buildIntentHint(intentClassification);
        String prompt = buildLlmPrompt(query, conversationContext, hasTimeWords, intentHint);

        try {
            String response = chatModel.chat(prompt).trim();
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                response = response.substring(jsonStart, jsonEnd + 1);
            }

            JsonNode json = objectMapper.readTree(response);
            RewriteResult result = new RewriteResult();
            result.setStrategy(RewriteResult.RewriteStrategy.LLM);

            result.setRewritten(json.has("rewritten") ? json.get("rewritten").asText() : query);

            // 后处理兜底：用户未提时间词但模型擅自添加了日期
            if (!hasTimeWords && DATE_PATTERN.matcher(result.getRewritten()).find()
                    && !DATE_PATTERN.matcher(query).find()) {
                log.warn("Query rewriter injected date despite no time words, falling back: '{}' -> '{}'",
                        result.getRewritten(), query);
                result.setRewritten(query);
            }

            // 解析 subQueries（向后兼容）
            if (json.has("subQueries") && json.get("subQueries").isArray()) {
                List<String> subs = new ArrayList<>();
                for (JsonNode sub : json.get("subQueries")) {
                    subs.add(sub.asText());
                }
                result.setSubQueries(subs);
            }
            if (result.getSubQueries().isEmpty()) {
                result.setSubQueries(List.of(result.getRewritten()));
            }

            // 解析 subTasks DAG
            List<SubTask> subTasks = parseSubTasks(json, intentClassification);
            if (subTasks.isEmpty()) {
                // 没有 DAG 输出 → 从 subQueries 构建平铺的无依赖任务
                subTasks = buildFlatSubTasks(result.getSubQueries(), intentClassification);
            }
            result.setSubTasks(subTasks);

            log.debug("LLM rewrite: '{}' -> '{}' ({} subTasks)",
                    query, result.getRewritten(), result.getSubTasks().size());
            return result;
        } catch (Exception e) {
            log.warn("Query rewrite failed for: {}", query, e);
            return buildPassthrough(query, System.currentTimeMillis());
        }
    }

    private String buildIntentHint(IntentClassification ic) {
        if (ic == null) return "";
        StringBuilder hint = new StringBuilder();
        hint.append("\n意图分类参考（hint）:\n");
        hint.append("- intent: ").append(ic.getPrimaryIntent()).append("\n");
        hint.append("- subIntent: ").append(ic.getSubIntent()).append("\n");
        if (ic.getSlots() != null && !ic.getSlots().isEmpty()) {
            hint.append("- slots: ").append(ic.getSlots()).append("\n");
        }
        return hint.toString();
    }

    private String buildLlmPrompt(String query, String conversationContext,
                                  boolean hasTimeWords, String intentHint) {
        String timeRule;
        String datePrefix;
        if (hasTimeWords) {
            datePrefix = "当前日期: " + java.time.LocalDate.now() + "\n";
            timeRule = "5. 将时间词替换为具体日期：\"今天\"指 " + java.time.LocalDate.now() + "，\"最近\"指最近7天";
        } else {
            datePrefix = "";
            timeRule = "5. 严禁添加任何日期、时间范围，保持原始查询的时间范围不变";
        }

        return datePrefix + """
                根据对话上下文，重写以下用户查询。返回严格的 JSON 格式（不要包含其他文字）:
                {
                  "rewritten": "重写后的主查询",
                  "subQueries": ["子查询1", "子查询2"],
                  "subTasks": [
                    {"id": "t1", "query": "子任务查询", "type": "QUESTION", "dependsOn": []},
                    {"id": "t2", "query": "子任务查询", "type": "COMPARISON_AXIS", "dependsOn": ["t1"]}
                  ]
                }

                规则：
                1. 解决指代消解：把"它"、"这个"、"那个"等替换为对话中提到的具体名词
                2. 如果是复合问题（如对比两个技术），拆分为独立 subTasks，并标注依赖关系
                3. 如果查询已经足够清晰且是单一问题，subTasks 只包含一个任务
                4. 重写后的查询应该独立可理解，不依赖上下文
                """ + timeRule + """

                6. subTasks 的 type 可选: QUESTION（信息查询）, TASK（操作任务）, COMPARISON_AXIS（对比维度，需依赖前驱任务的结果）
                7. 对比类问题：先拆分为各技术独立查询（dependsOn为空），再创建对比轴任务（dependsOn为前驱ID）
                8. subTasks 最多5个
                """ + intentHint + """

                对话上下文（最近几轮）:
                """ + SafeTemplates.s(conversationContext != null ? conversationContext : "无") + """

                当前用户查询: """ + SafeTemplates.s(query);
    }

    private List<SubTask> parseSubTasks(JsonNode json, IntentClassification ic) {
        if (!json.has("subTasks") || !json.get("subTasks").isArray()) {
            return Collections.emptyList();
        }

        List<SubTask> tasks = new ArrayList<>();
        for (JsonNode node : json.get("subTasks")) {
            String id = node.has("id") ? node.get("id").asText() : "t" + (tasks.size() + 1);
            String query = node.has("query") ? node.get("query").asText() : null;
            if (query == null || query.isBlank()) continue;

            SubTask.SubTaskType type = SubTask.SubTaskType.QUESTION;
            if (node.has("type")) {
                try {
                    type = SubTask.SubTaskType.valueOf(node.get("type").asText());
                } catch (IllegalArgumentException ignored) {}
            }

            List<String> deps = new ArrayList<>();
            if (node.has("dependsOn") && node.get("dependsOn").isArray()) {
                for (JsonNode dep : node.get("dependsOn")) {
                    deps.add(dep.asText());
                }
            }

            SubTask task = SubTask.builder()
                    .id(id)
                    .query(query)
                    .type(type)
                    .intent(ic != null ? ic.getPrimaryIntent() : Intent.GENERAL_CHAT)
                    .subIntent(ic != null ? ic.getSubIntent() : SubIntent.UNKNOWN)
                    .slots(ic != null && ic.getSlots() != null ? new HashMap<>(ic.getSlots()) : new HashMap<>())
                    .dependsOn(deps)
                    .build();
            tasks.add(task);
        }
        return tasks;
    }

    private List<SubTask> buildFlatSubTasks(List<String> subQueries, IntentClassification ic) {
        List<SubTask> tasks = new ArrayList<>();
        int i = 1;
        for (String q : subQueries) {
            SubTask task = SubTask.builder()
                    .id("t" + i)
                    .query(q)
                    .type(SubTask.SubTaskType.QUESTION)
                    .intent(ic != null ? ic.getPrimaryIntent() : Intent.GENERAL_CHAT)
                    .subIntent(ic != null ? ic.getSubIntent() : SubIntent.UNKNOWN)
                    .slots(ic != null && ic.getSlots() != null ? new HashMap<>(ic.getSlots()) : new HashMap<>())
                    .dependsOn(Collections.emptyList())
                    .build();
            tasks.add(task);
            i++;
        }
        return tasks;
    }

    // ---- 便捷工具 ----

    private RewriteResult buildPassthrough(String query, long startTime) {
        RewriteResult result = new RewriteResult();
        result.setRewritten(query);
        result.setSubQueries(List.of(query));
        result.setStrategy(RewriteResult.RewriteStrategy.RULE_PASSTHROUGH);
        result.setLatencyMs(System.currentTimeMillis() - startTime);

        SubTask task = SubTask.builder()
                .id("t1")
                .query(query)
                .type(SubTask.SubTaskType.QUESTION)
                .dependsOn(Collections.emptyList())
                .build();
        result.setSubTasks(List.of(task));
        return result;
    }
}
