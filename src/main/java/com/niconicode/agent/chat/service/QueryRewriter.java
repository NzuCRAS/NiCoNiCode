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
public class QueryRewriter {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        // 短查询或无上下文时跳过重写
        if (query.length() <= 10 && (conversationContext == null || conversationContext.isBlank())) {
            RewriteResult result = new RewriteResult();
            result.setRewritten(query);
            result.setSubQueries(List.of(query));
            return result;
        }

        String prompt = """
                根据对话上下文，重写以下用户查询。返回严格的 JSON 格式（不要包含其他文字）:
                {"rewritten": "重写后的查询", "subQueries": ["子查询1", "子查询2"]}

                规则：
                1. 解决指代消解：把"它"、"这个"、"那个"等替换为对话中提到的具体名词
                2. 如果是复合问题（如对比两个技术），拆分为独立子查询
                3. 如果查询已经足够清晰且是单一问题，subQueries 只包含 rewritten 本身
                4. 重写后的查询应该独立可理解，不依赖上下文

                对话上下文（最近几轮）:
                %s

                当前用户查询: %s
                """.formatted(
                conversationContext != null ? conversationContext : "无",
                query);

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
