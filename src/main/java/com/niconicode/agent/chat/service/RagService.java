package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.mapper.KnowledgeDocMapper;
import com.niconicode.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final KnowledgeService knowledgeService;
    private final KnowledgeDocMapper knowledgeDocMapper;

    /**
     * 原始单通道检索（向后兼容）
     */
    public String retrieveContext(String query) {
        return retrieveContext(query, null);
    }

    /**
     * 双通道并行检索 + 合并去重 + 重排序
     * 通道1: 向量语义搜索
     * 通道2: 意图定向关键词搜索
     */
    public String retrieveContext(String query, String intent) {
        // 通道1: 向量语义搜索
        CompletableFuture<List<Map<String, Object>>> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return knowledgeService.semanticSearch(query, 5);
                    } catch (Exception e) {
                        log.warn("Vector search failed", e);
                        return Collections.emptyList();
                    }
                });

        // 通道2: 关键词搜索（基于意图选择搜索范围）
        CompletableFuture<List<Map<String, Object>>> keywordFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return keywordSearch(query, intent);
                    } catch (Exception e) {
                        log.warn("Keyword search failed", e);
                        return Collections.emptyList();
                    }
                });

        // 合并 + 去重 + 重排序
        List<Map<String, Object>> vectorResults = vectorFuture.join();
        List<Map<String, Object>> keywordResults = keywordFuture.join();

        List<Map<String, Object>> merged = mergeAndDedup(vectorResults, keywordResults);
        List<Map<String, Object>> reranked = rerank(merged, query);

        if (reranked.isEmpty()) {
            return "";
        }

        return reranked.stream()
                .limit(5)
                .map(r -> "【" + r.getOrDefault("title", "知识库") + "】\n" + r.getOrDefault("text", ""))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 关键词搜索：基于 MySQL 全文索引 + LIKE 查询
     */
    private List<Map<String, Object>> keywordSearch(String query, String intent) {
        // 提取查询中的关键词（简单分词）
        String[] keywords = query.replaceAll("[?？！!。，,.\\s]+", " ").trim().split("\\s+");
        if (keywords.length == 0) return Collections.emptyList();

        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getStatus, "ACTIVE");

        // 根据意图选择搜索范围
        if ("VERSION_UPDATE".equals(intent) || "REPORT_QUERY".equals(intent)) {
            wrapper.eq(KnowledgeDoc::getSourceType, "TRACKER_REPORT");
        }

        // 构建 OR 查询
        wrapper.and(w -> {
            for (String keyword : keywords) {
                if (keyword.length() >= 2) {
                    w.or(sub -> sub.like(KnowledgeDoc::getTitle, keyword)
                            .or().like(KnowledgeDoc::getTags, keyword));
                }
            }
        });
        wrapper.last("LIMIT 5");

        List<KnowledgeDoc> docs = knowledgeDocMapper.selectList(wrapper);

        return docs.stream().map(doc -> {
            Map<String, Object> result = new HashMap<>();
            result.put("title", doc.getTitle());
            result.put("text", doc.getContent().length() > 800
                    ? doc.getContent().substring(0, 800) : doc.getContent());
            result.put("source_id", doc.getId());
            result.put("source", "keyword");
            result.put("score", 0.5); // 关键词搜索默认分数
            // 根据命中关键词数量调整分数
            String contentLower = (doc.getTitle() + " " + doc.getTags()).toLowerCase();
            long matchCount = Arrays.stream(keywords)
                    .filter(k -> k.length() >= 2 && contentLower.contains(k.toLowerCase()))
                    .count();
            result.put("score", Math.min(1.0, 0.3 + matchCount * 0.2));
            return result;
        }).collect(Collectors.toList());
    }

    /**
     * 合并两个通道的结果并按 source_id 去重
     */
    private List<Map<String, Object>> mergeAndDedup(
            List<Map<String, Object>> vectorResults,
            List<Map<String, Object>> keywordResults) {

        Map<Object, Map<String, Object>> seen = new LinkedHashMap<>();

        // 向量结果优先
        for (Map<String, Object> result : vectorResults) {
            Object sourceId = result.get("source_id");
            String key = sourceId != null ? sourceId.toString() : result.getOrDefault("title", "").toString();
            if (!seen.containsKey(key)) {
                result.putIfAbsent("source", "vector");
                seen.put(key, result);
            }
        }

        // 合并关键词结果
        for (Map<String, Object> result : keywordResults) {
            Object sourceId = result.get("source_id");
            String key = sourceId != null ? sourceId.toString() : result.getOrDefault("title", "").toString();
            if (!seen.containsKey(key)) {
                seen.put(key, result);
            } else {
                // 两个通道都命中的，提升分数
                Map<String, Object> existing = seen.get(key);
                double existingScore = ((Number) existing.getOrDefault("score", 0.5)).doubleValue();
                double keywordScore = ((Number) result.getOrDefault("score", 0.5)).doubleValue();
                existing.put("score", Math.min(1.0, existingScore + keywordScore * 0.3));
                existing.put("source", "both");
            }
        }

        return new ArrayList<>(seen.values());
    }

    /**
     * 简单重排序：向量分数 * 0.6 + 关键词匹配分 * 0.4
     */
    private List<Map<String, Object>> rerank(List<Map<String, Object>> results, String query) {
        String queryLower = query.toLowerCase();
        for (Map<String, Object> result : results) {
            double baseScore = ((Number) result.getOrDefault("score", 0.5)).doubleValue();
            String title = result.getOrDefault("title", "").toString().toLowerCase();
            String text = result.getOrDefault("text", "").toString().toLowerCase();

            // 标题完全包含查询关键词加分
            double titleBoost = title.contains(queryLower) ? 0.2 : 0;
            // 内容相关性
            double contentBoost = text.contains(queryLower) ? 0.1 : 0;

            result.put("finalScore", baseScore * 0.6 + (baseScore + titleBoost + contentBoost) * 0.4);
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("finalScore", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("finalScore", 0.0)).doubleValue()));

        return results;
    }
}
