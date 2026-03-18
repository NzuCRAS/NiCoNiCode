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

@Slf4j
@Service
public class QueryRewriter {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public QueryRewriter(@Qualifier("fastChatModel") ChatLanguageModel chatModel) {
        this.chatModel = chatModel;
    }

    @Data
    public static class RewriteResult {
        private String rewritten;
        private List<String> subQueries = new ArrayList<>();
    }

    /**
     * 根据对话上下文重写用户查询：
     * 1. 指代消解（"它" → "Spring Boot"）
     * 2. 复合问题拆分为子查询
     */
    public RewriteResult rewrite(String query, String conversationContext) {
        // 极短查询无条件绕过（不管有无上下文）
        if (query.length() <= 5) {
            RewriteResult result = new RewriteResult();
            result.setRewritten(query);
            result.setSubQueries(List.of(query));
            return result;
        }

        // 短查询或无上下文时跳过重写
        if (query.length() <= 10 && (conversationContext == null || conversationContext.isBlank())) {
            RewriteResult result = new RewriteResult();
            result.setRewritten(query);
            result.setSubQueries(List.of(query));
            return result;
        }

    // 用户没提“今天/最近/某天”等时间词时，不要强行把查询改写成某个具体日期
    // 否则会出现："最有价值的报道" -> "2026-xx-xx 最有价值的报道" 这种不符合用户意图的重写
    boolean hasTimeWords = query.matches("(?s).*(今天|昨日|昨天|前天|最近|近\\d+天|本周|这周|上周|本月|这个月|上个月|\\d{4}-\\d{2}-\\d{2}).*");

        String prompt;
        if (hasTimeWords) {
            prompt = """
                    当前日期: %s
                    根据对话上下文，重写以下用户查询。返回严格的 JSON 格式（不要包含其他文字）:
                    {"rewritten": "重写后的查询", "subQueries": ["子查询1", "子查询2"]}

                    规则：
                    1. 解决指代消解：把"它"、"这个"、"那个"等替换为对话中提到的具体名词
                    2. 如果是复合问题（如对比两个技术），拆分为独立子查询
                    3. 如果查询已经足够清晰且是单一问题，subQueries 只包含 rewritten 本身
                    4. 重写后的查询应该独立可理解，不依赖上下文
                    5. 将时间词替换为具体日期："今天"指 %s，"最近"指最近7天

                    对话上下文（最近几轮）:
                    %s

                    当前用户查询: %s
                    """.formatted(
                    java.time.LocalDate.now().toString(),
                    java.time.LocalDate.now().toString(),
                    conversationContext != null ? conversationContext : "无",
                    query);
        } else {
            // 不含时间词时：完全不提日期信息，防止模型自作主张添加日期
            prompt = """
                    根据对话上下文，重写以下用户查询。返回严格的 JSON 格式（不要包含其他文字）:
                    {"rewritten": "重写后的查询", "subQueries": ["子查询1", "子查询2"]}

                    规则：
                    1. 解决指代消解：把"它"、"这个"、"那个"等替换为对话中提到的具体名词
                    2. 如果是复合问题（如对比两个技术），拆分为独立子查询
                    3. 如果查询已经足够清晰且是单一问题，subQueries 只包含 rewritten 本身
                    4. 重写后的查询应该独立可理解，不依赖上下文
                    5. 严禁添加任何日期、时间范围，保持原始查询的时间范围不变

                    对话上下文（最近几轮）:
                    %s

                    当前用户查询: %s
                    """.formatted(
                    conversationContext != null ? conversationContext : "无",
                    query);
        }

        try {
            String response = chatModel.chat(prompt).trim();
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                response = response.substring(jsonStart, jsonEnd + 1);
            }

            JsonNode json = objectMapper.readTree(response);
            RewriteResult result = new RewriteResult();

            result.setRewritten(json.has("rewritten") ? json.get("rewritten").asText() : query);

            // 后处理兜底：用户未提时间词但模型擅自添加了日期 → 回退到原始查询
            if (!hasTimeWords && DATE_PATTERN.matcher(result.getRewritten()).find()
                    && !DATE_PATTERN.matcher(query).find()) {
                log.warn("Query rewriter injected date despite no time words, falling back: '{}' -> '{}'",
                        result.getRewritten(), query);
                result.setRewritten(query);
            }

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

            log.debug("Query rewritten: '{}' -> '{}' ({} sub-queries)",
                    query, result.getRewritten(), result.getSubQueries().size());
            return result;
        } catch (Exception e) {
            log.warn("Query rewrite failed for: {}", query, e);
            RewriteResult fallback = new RewriteResult();
            fallback.setRewritten(query);
            fallback.setSubQueries(List.of(query));
            return fallback;
        }
    }
}
