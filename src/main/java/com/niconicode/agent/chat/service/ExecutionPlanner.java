package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.IntentClassification;
import com.niconicode.agent.chat.dto.IntentClassification.Intent;
import com.niconicode.agent.chat.dto.IntentClassification.SubIntent;
import com.niconicode.agent.chat.dto.SubTask;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流执行规划器 — 根据意图分类确定性地映射到工具调用，
 * 完全取代 ReAct 循环中"让 LLM 决定调什么工具"的过程。
 *
 * 聊天场景的工具集固定（7个）、意图可枚举，不需要 LLM 做工具选择。
 * ReAct（LLM 自主决策）仅保留给狗仔 Agent 等真正需要动态推理的场景。
 */
@Slf4j
@Service
public class ExecutionPlanner {

    public enum ExecutionStrategy {
        DIRECT_ANSWER,    // 简单闲聊，无工具无 RAG
        RAG_ONLY,         // 知识查询，仅 RAG
        WORKFLOW,         // 工作流：确定性工具调用（取代 SINGLE_TOOL + TOOL_PLUS_RAG + PLAN_AND_EXECUTE）
    }

    /**
     * 工作流中的一个工具调用指令
     */
    @Data
    public static class ToolCall {
        private final String toolName;
        private final String arguments;  // JSON 格式参数

        public static ToolCall of(String toolName, String argsJson) {
            return new ToolCall(toolName, argsJson);
        }
    }

    /**
     * 工作流执行计划：包含要调用的工具列表 + 是否需要 RAG
     */
    @Data
    public static class WorkflowPlan {
        private final ExecutionStrategy strategy;
        private final List<ToolCall> toolCalls;
        private final boolean needsRag;

        public static WorkflowPlan direct() {
            return new WorkflowPlan(ExecutionStrategy.DIRECT_ANSWER, List.of(), false);
        }

        public static WorkflowPlan ragOnly() {
            return new WorkflowPlan(ExecutionStrategy.RAG_ONLY, List.of(), true);
        }

        public static WorkflowPlan workflow(List<ToolCall> calls, boolean needsRag) {
            return new WorkflowPlan(ExecutionStrategy.WORKFLOW, calls, needsRag);
        }
    }

    private static final Set<SubIntent> DIRECT_ANSWER_INTENTS = Set.of(
            SubIntent.GREETING, SubIntent.FAREWELL, SubIntent.THANKS,
            SubIntent.CONFIRMATION, SubIntent.ABOUT_BOT
    );

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 核心方法：根据意图 + 槽位 + 重写查询，生成确定性执行计划
     */
    public WorkflowPlan plan(IntentClassification intent, List<SubTask> subTasks,
                             String rewrittenQuery) {
        if (intent == null) {
            return WorkflowPlan.ragOnly();
        }

        Intent primary = intent.getPrimaryIntent();
        SubIntent sub = intent.getSubIntent();
        Map<String, String> slots = intent.getSlots() != null ? intent.getSlots() : Map.of();
        String techName = slots.getOrDefault("techName", "");

        // ---- GENERAL_CHAT ----
        if (primary == Intent.GENERAL_CHAT) {
            if (DIRECT_ANSWER_INTENTS.contains(sub)) {
                return WorkflowPlan.direct();
            }
            return WorkflowPlan.ragOnly();
        }

        // ---- UNCLEAR ----
        if (primary == Intent.UNCLEAR) {
            return WorkflowPlan.ragOnly();
        }

        // ---- VERSION_UPDATE ----
        if (primary == Intent.VERSION_UPDATE) {
            if (!techName.isBlank()) {
                List<ToolCall> calls = new ArrayList<>();
                calls.add(ToolCall.of("getTechInfo", jsonArg("techName", techName)));
                calls.add(ToolCall.of("getRecentReportsForTech", jsonArg("techName", techName)));
                return WorkflowPlan.workflow(calls, false);
            }
            // 无具体技术名 → 用搜索
            return WorkflowPlan.workflow(
                    List.of(ToolCall.of("searchReports", jsonArg("keyword", rewrittenQuery))),
                    true);
        }

        // ---- REPORT_QUERY ----
        if (primary == Intent.REPORT_QUERY) {
            return planReportQuery(sub, slots, techName, rewrittenQuery);
        }

        // ---- COMPARISON ----
        if (primary == Intent.COMPARISON) {
            return planComparison(slots, subTasks, rewrittenQuery);
        }

        // ---- TECH_QUERY ----
        if (primary == Intent.TECH_QUERY) {
            List<ToolCall> calls = new ArrayList<>();
            if (!techName.isBlank()) {
                calls.add(ToolCall.of("getTechInfo", jsonArg("techName", techName)));
            }
            // TECH_QUERY 始终需要 RAG 补充知识
            return WorkflowPlan.workflow(calls, true);
        }

        // ---- CODE_HELP ----
        if (primary == Intent.CODE_HELP) {
            List<ToolCall> calls = new ArrayList<>();
            if (!techName.isBlank()) {
                calls.add(ToolCall.of("knowledgeSearch", jsonArg("query", techName + " " + sub.name())));
            } else {
                calls.add(ToolCall.of("knowledgeSearch", jsonArg("query", rewrittenQuery)));
            }
            return WorkflowPlan.workflow(calls, true);
        }

        // ---- GITHUB_ANALYSIS ----
        if (primary == Intent.GITHUB_ANALYSIS) {
            if (!techName.isBlank()) {
                List<ToolCall> calls = new ArrayList<>();
                calls.add(ToolCall.of("getTechInfo", jsonArg("techName", techName)));
                calls.add(ToolCall.of("getRecentReportsForTech", jsonArg("techName", techName)));
                return WorkflowPlan.workflow(calls, false);
            }
            return WorkflowPlan.ragOnly();
        }

        // 兜底
        return WorkflowPlan.ragOnly();
    }

    // ---- 子规划方法 ----

    private WorkflowPlan planReportQuery(SubIntent sub, Map<String, String> slots,
                                          String techName, String rewrittenQuery) {
        List<ToolCall> calls = new ArrayList<>();

        switch (sub) {
            case LATEST_NEWS -> {
                if (!techName.isBlank()) {
                    calls.add(ToolCall.of("getRecentReportsForTech", jsonArg("techName", techName)));
                } else {
                    // 尝试从重写查询中提取日期
                    String[] dates = extractDateRange(rewrittenQuery);
                    if (dates != null) {
                        calls.add(ToolCall.of("getReportsByDate",
                                "{\"startDate\":\"" + dates[0] + "\",\"endDate\":\"" + dates[1] + "\"}"));
                    } else {
                        // 默认查最近 7 天
                        String today = LocalDate.now().toString();
                        String weekAgo = LocalDate.now().minusDays(7).toString();
                        calls.add(ToolCall.of("getReportsByDate",
                                "{\"startDate\":\"" + weekAgo + "\",\"endDate\":\"" + today + "\"}"));
                    }
                }
            }
            case TECH_NEWS -> {
                if (!techName.isBlank()) {
                    calls.add(ToolCall.of("searchReports", jsonArg("keyword", techName)));
                } else {
                    calls.add(ToolCall.of("searchReports", jsonArg("keyword", rewrittenQuery)));
                }
            }
            case TRENDING -> {
                calls.add(ToolCall.of("listTrackedTechnologies", "{}"));
            }
            default -> {
                // 通用报道查询：尝试日期 → 关键词搜索
                String[] dates = extractDateRange(rewrittenQuery);
                if (dates != null) {
                    calls.add(ToolCall.of("getReportsByDate",
                            "{\"startDate\":\"" + dates[0] + "\",\"endDate\":\"" + dates[1] + "\"}"));
                } else {
                    calls.add(ToolCall.of("searchReports",
                            jsonArg("keyword", !techName.isBlank() ? techName : rewrittenQuery)));
                }
            }
        }

        return WorkflowPlan.workflow(calls, false);
    }

    private WorkflowPlan planComparison(Map<String, String> slots, List<SubTask> subTasks,
                                         String rewrittenQuery) {
        List<ToolCall> calls = new ArrayList<>();

        // 从 slots 中提取多个技术名
        String techName = slots.getOrDefault("techName", "");
        String techName2 = slots.getOrDefault("techName2", "");

        if (!techName.isBlank()) {
            calls.add(ToolCall.of("getTechInfo", jsonArg("techName", techName)));
        }
        if (!techName2.isBlank()) {
            calls.add(ToolCall.of("getTechInfo", jsonArg("techName", techName2)));
        }

        // 也从 subTasks 中尝试提取技术名
        if (subTasks != null) {
            for (SubTask task : subTasks) {
                String st = task.getSlots() != null ? task.getSlots().getOrDefault("techName", "") : "";
                if (!st.isBlank() && !st.equals(techName) && !st.equals(techName2)) {
                    calls.add(ToolCall.of("getTechInfo", jsonArg("techName", st)));
                }
            }
        }

        // 无法提取到具体技术名 → 用搜索兜底
        if (calls.isEmpty()) {
            calls.add(ToolCall.of("searchReports", jsonArg("keyword", rewrittenQuery)));
        }

        return WorkflowPlan.workflow(calls, true);
    }

    // ---- 工具方法 ----

    /**
     * 从重写后的查询中提取日期范围
     */
    private String[] extractDateRange(String query) {
        if (query == null) return null;
        Matcher m = DATE_PATTERN.matcher(query);
        List<String> dates = new ArrayList<>();
        while (m.find() && dates.size() < 2) {
            dates.add(m.group());
        }
        if (dates.size() == 2) {
            return new String[]{dates.get(0), dates.get(1)};
        } else if (dates.size() == 1) {
            return new String[]{dates.get(0), dates.get(0)};
        }
        return null;
    }

    private static String jsonArg(String key, String value) {
        return "{\"" + key + "\":\"" + value.replace("\"", "\\\"") + "\"}";
    }
}
