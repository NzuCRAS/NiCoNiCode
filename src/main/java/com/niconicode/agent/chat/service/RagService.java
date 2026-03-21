package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.mapper.KnowledgeDocMapper;
import com.niconicode.knowledge.service.KnowledgeService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final KnowledgeService knowledgeService;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final RagCacheService ragCacheService;

    /**
     * 原始单通道检索（向后兼容）
     */
    public String retrieveContext(String query) {
        return retrieveContext(query, null);
    }

    /**
     * 双通道并行检索 + 合并去重 + 重排序（带缓存）
     */
    public String retrieveContext(String query, String intent) {
        return retrieveContext(query, intent, null);
    }

    /**
     * 双通道并行检索 + 合并去重 + 重排序 + 会话级去重
     * @param excludeDocIds 已检索过的文档 ID 集合，跳过这些文档
     */
    public String retrieveContext(String query, String intent, Set<String> excludeDocIds) {
        // 缓存检查
        String cached = ragCacheService.getCached(query);
        if (cached != null) {
            return cached;
        }

        long start = System.currentTimeMillis();
        // 通道1: 向量语义搜索
        CompletableFuture<List<Map<String, Object>>> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return knowledgeService.semanticSearch(query, 5);
                    } catch (Exception e) {
                        log.warn("Vector search failed", e);
                        return Collections.emptyList();
                    } finally {
                        long d = System.currentTimeMillis();
                        log.debug("[RAG] vectorSearch done, duration={}ms", (d - start));
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
                    } finally {
                        long d = System.currentTimeMillis();
                        log.debug("[RAG] keywordSearch done, duration={}ms", (d - start));
                    }
                });

        // 合并 + 去重 + 重排序
        List<Map<String, Object>> vectorResults;
        List<Map<String, Object>> keywordResults;
        try {
            vectorResults = vectorFuture.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[RAG] vectorSearch timeout/fail: {}", e.getMessage());
            vectorResults = Collections.emptyList();
        }
        try {
            keywordResults = keywordFuture.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[RAG] keywordSearch timeout/fail: {}", e.getMessage());
            keywordResults = Collections.emptyList();
        }

        List<Map<String, Object>> merged = mergeAndDedup(vectorResults, keywordResults, excludeDocIds);
        List<Map<String, Object>> reranked = rerank(merged, query);

        if (reranked.isEmpty()) {
            log.debug("[RAG] merged empty, totalDuration={}ms", (System.currentTimeMillis() - start));
            return "";
        }

        log.debug("[RAG] merged size={}, totalDuration={}ms", reranked.size(), (System.currentTimeMillis() - start));
        String result = reranked.stream()
                .limit(5)
                .map(r -> {
                    String title = String.valueOf(r.getOrDefault("title", "知识库"));
                    Object sourceId = r.get("source_id");
                    String idTag = sourceId != null ? "[文档#" + sourceId + "]" : "";
                    return "【" + title + "】" + idTag + "\n" + r.getOrDefault("text", "");
                })
                .collect(Collectors.joining("\n\n"));

        // 缓存结果
        ragCacheService.put(query, result);
        return result;
    }

    /**
     * 关键词搜索：基于 MySQL 全文索引 + LIKE 查询
     */
    private List<Map<String, Object>> keywordSearch(String query, String intent) {
        String[] keywords = query.replaceAll("[?？！!。，,.\\s]+", " ").trim().split("\\s+");
        if (keywords.length == 0) return Collections.emptyList();

        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getStatus, "ACTIVE");

        if ("VERSION_UPDATE".equals(intent) || "REPORT_QUERY".equals(intent)) {
            wrapper.eq(KnowledgeDoc::getSourceType, "TRACKER_REPORT");
        }

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
            result.put("score", 0.5);
            if (doc.getUpdatedAt() != null) {
                result.put("updatedAt", doc.getUpdatedAt());
            } else if (doc.getCreatedAt() != null) {
                result.put("updatedAt", doc.getCreatedAt());
            }
            String contentLower = (doc.getTitle() + " " + doc.getTags()).toLowerCase();
            long matchCount = Arrays.stream(keywords)
                    .filter(k -> k.length() >= 2 && contentLower.contains(k.toLowerCase()))
                    .count();
            result.put("score", Math.min(1.0, 0.3 + matchCount * 0.2));
            return result;
        }).collect(Collectors.toList());
    }

    /**
     * 合并两个通道的结果并按 source_id 去重 + 会话级去重
     */
    private List<Map<String, Object>> mergeAndDedup(
            List<Map<String, Object>> vectorResults,
            List<Map<String, Object>> keywordResults,
            Set<String> excludeDocIds) {

        Map<Object, Map<String, Object>> seen = new LinkedHashMap<>();

        for (Map<String, Object> result : vectorResults) {
            Object sourceId = result.get("source_id");
            String key = sourceId != null ? sourceId.toString() : result.getOrDefault("title", "").toString();
            // 会话级去重
            if (excludeDocIds != null && excludeDocIds.contains(key)) continue;
            if (!seen.containsKey(key)) {
                result.putIfAbsent("source", "vector");
                seen.put(key, result);
            }
        }

        for (Map<String, Object> result : keywordResults) {
            Object sourceId = result.get("source_id");
            String key = sourceId != null ? sourceId.toString() : result.getOrDefault("title", "").toString();
            if (excludeDocIds != null && excludeDocIds.contains(key)) continue;
            if (!seen.containsKey(key)) {
                seen.put(key, result);
            } else {
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
     * 带结构化元数据的检索（用于思考链展示）
     */
    public RagResult retrieveContextWithMeta(String query, String intent, Set<String> excludeDocIds) {
        // 缓存检查
        String cached = ragCacheService.getCached(query);
        if (cached != null) {
            return new RagResult(cached, List.of());
        }

        long start = System.currentTimeMillis();
        CompletableFuture<List<Map<String, Object>>> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    try { return knowledgeService.semanticSearch(query, 5); }
                    catch (Exception e) { log.warn("Vector search failed", e); return Collections.emptyList(); }
                });
        CompletableFuture<List<Map<String, Object>>> keywordFuture =
                CompletableFuture.supplyAsync(() -> {
                    try { return keywordSearch(query, intent); }
                    catch (Exception e) { log.warn("Keyword search failed", e); return Collections.emptyList(); }
                });

        List<Map<String, Object>> vectorResults, keywordResults;
        try { vectorResults = vectorFuture.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { vectorResults = Collections.emptyList(); }
        try { keywordResults = keywordFuture.get(2, TimeUnit.SECONDS); }
        catch (Exception e) { keywordResults = Collections.emptyList(); }

        List<Map<String, Object>> merged = mergeAndDedup(vectorResults, keywordResults, excludeDocIds);
        List<Map<String, Object>> reranked = rerank(merged, query);

        if (reranked.isEmpty()) {
            return new RagResult("", List.of());
        }

        List<Map<String, Object>> top = reranked.stream().limit(5).toList();

        // 构建结构化元数据
        List<RagDoc> docs = top.stream().map(r -> {
            Object sid = r.get("source_id");
            long id = 0;
            if (sid instanceof Number n) id = n.longValue();
            else if (sid != null) { try { id = Long.parseLong(sid.toString()); } catch (NumberFormatException ignored) {} }
            String title = String.valueOf(r.getOrDefault("title", ""));
            double score = ((Number) r.getOrDefault("finalScore", r.getOrDefault("score", 0.0))).doubleValue();
            return new RagDoc(id, title, score);
        }).filter(d -> d.getId() > 0).toList();

        String context = top.stream()
                .map(r -> {
                    String title = String.valueOf(r.getOrDefault("title", "知识库"));
                    Object sourceId = r.get("source_id");
                    String idTag = sourceId != null ? "[文档#" + sourceId + "]" : "";
                    return "【" + title + "】" + idTag + "\n" + r.getOrDefault("text", "");
                })
                .collect(Collectors.joining("\n\n"));

        ragCacheService.put(query, context);
        log.debug("[RAG] retrieveContextWithMeta size={}, duration={}ms", top.size(), (System.currentTimeMillis() - start));
        return new RagResult(context, docs);
    }

    @Data
    @AllArgsConstructor
    public static class RagResult {
        private String context;
        private List<RagDoc> docs;
    }

    @Data
    @AllArgsConstructor
    public static class RagDoc {
        private long id;
        private String title;
        private double score;
    }

    /**
     * 增强重排序：Token 重叠率 + 长度归一化 + 来源加权 + 时效性加分
     */
    private List<Map<String, Object>> rerank(List<Map<String, Object>> results, String query) {
        String queryLower = query.toLowerCase();
        // 分词用于 token 重叠率
        String[] queryTokens = queryLower.replaceAll("[?？！!。，,.\\s]+", " ").trim().split("\\s+");

        for (Map<String, Object> result : results) {
            double baseScore = ((Number) result.getOrDefault("score", 0.5)).doubleValue();
            String title = result.getOrDefault("title", "").toString().toLowerCase();
            String text = result.getOrDefault("text", "").toString().toLowerCase();
            String fullContent = title + " " + text;

            // 标题完全包含查询关键词加分
            double titleBoost = title.contains(queryLower) ? 0.2 : 0;
            // 内容相关性
            double contentBoost = text.contains(queryLower) ? 0.1 : 0;

            // Token 重叠率
            long matchedTokens = Arrays.stream(queryTokens)
                    .filter(t -> t.length() >= 2 && fullContent.contains(t))
                    .count();
            double tokenOverlap = queryTokens.length > 0
                    ? (double) matchedTokens / queryTokens.length * 0.15
                    : 0;

            // 长度归一化：过短或过长降权
            int textLen = text.length();
            double lengthPenalty = 0;
            if (textLen < 50) lengthPenalty = -0.1;
            else if (textLen > 2000) lengthPenalty = -0.05;

            // 来源加权：两个通道都命中额外加分
            double sourceBoost = "both".equals(result.getOrDefault("source", "")) ? 0.15 : 0;

            // 时效性加分
            double recencyBoost = 0;
            Object updatedAtObj = result.get("updatedAt");
            if (updatedAtObj instanceof LocalDateTime updatedAt) {
                long daysSince = ChronoUnit.DAYS.between(updatedAt, LocalDateTime.now());
                if (daysSince <= 7) recencyBoost = 0.1;
                else if (daysSince <= 30) recencyBoost = 0.05;
            }

            double finalScore = baseScore * 0.6
                    + (baseScore + titleBoost + contentBoost + tokenOverlap + sourceBoost + recencyBoost + lengthPenalty) * 0.4;
            result.put("finalScore", finalScore);
        }

        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("finalScore", 0.0)).doubleValue(),
                ((Number) a.getOrDefault("finalScore", 0.0)).doubleValue()));

        return results;
    }
}
