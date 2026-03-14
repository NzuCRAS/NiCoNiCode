package com.niconicode.knowledge.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档入库 ETL 管道
 * 流程: 解析 → 清洗 → 分块 → 向量化 → 写入 Qdrant
 * 替代 KnowledgeService.asyncEmbedAndStore() 中的内联逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentETLService {

    private final EmbeddingService embeddingService;
    private final VectorService vectorService;

    private static final int DEFAULT_CHUNK_SIZE = 800;

    @Data
    public static class ETLResult {
        private Long docId;
        private boolean success;
        private String error;
        private int chunkCount;
        private long parseDurationMs;
        private long cleanDurationMs;
        private long chunkDurationMs;
        private long vectorizeDurationMs;
        private long storeDurationMs;
        private long totalDurationMs;

        public ETLResult(Long docId) {
            this.docId = docId;
        }
    }

    /**
     * 执行完整的 ETL 管道
     */
    public ETLResult process(Long docId, String title, String content,
                             String sourceType, String tags) {
        ETLResult result = new ETLResult(docId);
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: 解析 - 提取纯文本
            long stepStart = System.currentTimeMillis();
            String parsedText = parse(content);
            result.setParseDurationMs(System.currentTimeMillis() - stepStart);
            log.debug("[ETL] Doc {}: parse completed ({}ms)", docId, result.getParseDurationMs());

            // Step 2: 清洗 - 去除无用标记
            stepStart = System.currentTimeMillis();
            String cleanText = clean(parsedText);
            result.setCleanDurationMs(System.currentTimeMillis() - stepStart);
            log.debug("[ETL] Doc {}: clean completed ({}ms)", docId, result.getCleanDurationMs());

            // Step 3: 分块 - 按段落/固定长度分割
            stepStart = System.currentTimeMillis();
            List<String> chunks = chunk(cleanText, DEFAULT_CHUNK_SIZE);
            result.setChunkCount(chunks.size());
            result.setChunkDurationMs(System.currentTimeMillis() - stepStart);
            log.debug("[ETL] Doc {}: chunked into {} pieces ({}ms)",
                    docId, chunks.size(), result.getChunkDurationMs());

            // Step 4: 向量化 - 调用 EmbeddingService
            stepStart = System.currentTimeMillis();
            List<float[]> vectors = vectorize(chunks);
            result.setVectorizeDurationMs(System.currentTimeMillis() - stepStart);
            log.debug("[ETL] Doc {}: vectorized {} chunks ({}ms)",
                    docId, vectors.size(), result.getVectorizeDurationMs());

            // Step 5: 写入 Qdrant
            stepStart = System.currentTimeMillis();
            store(vectors, chunks, docId, title, sourceType, tags);
            result.setStoreDurationMs(System.currentTimeMillis() - stepStart);
            log.debug("[ETL] Doc {}: stored to Qdrant ({}ms)", docId, result.getStoreDurationMs());

            result.setSuccess(true);
            result.setTotalDurationMs(System.currentTimeMillis() - startTime);
            log.info("[ETL] Doc {} completed: {} chunks in {}ms",
                    docId, chunks.size(), result.getTotalDurationMs());

        } catch (Exception e) {
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setTotalDurationMs(System.currentTimeMillis() - startTime);
            log.error("[ETL] Doc {} failed after {}ms: {}",
                    docId, result.getTotalDurationMs(), e.getMessage());
        }

        return result;
    }

    /**
     * Step 1: 解析 - 目前直接返回原文，后续可扩展支持 HTML/PDF
     */
    private String parse(String content) {
        if (content == null) return "";
        return content;
    }

    /**
     * Step 2: 清洗 - 去除无用标记
     */
    private String clean(String text) {
        // 去除外层 Markdown 代码块包裹
        String cleaned = text.trim();
        if (cleaned.startsWith("```markdown") || cleaned.startsWith("```md")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0 && cleaned.endsWith("```")) {
                cleaned = cleaned.substring(firstNewline + 1, cleaned.length() - 3).trim();
            }
        }
        // 去除连续空行（保留最多两个换行）
        cleaned = cleaned.replaceAll("\n{4,}", "\n\n\n");
        // 去除不可见字符
        cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return cleaned;
    }

    /**
     * Step 3: 分块 - 按段落分割，支持配置最大字符数
     */
    private List<String> chunk(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            // 如果单个段落超过 maxChars，强制按长度分割
            if (p.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
                for (int i = 0; i < p.length(); i += maxChars) {
                    chunks.add(p.substring(i, Math.min(i + maxChars, p.length())).trim());
                }
                continue;
            }

            if (current.length() + p.length() > maxChars && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(p).append("\n\n");
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        if (chunks.isEmpty()) {
            chunks.add(content);
        }
        return chunks;
    }

    /**
     * Step 4: 向量化
     */
    private List<float[]> vectorize(List<String> chunks) {
        List<float[]> vectors = new ArrayList<>();
        for (String chunk : chunks) {
            vectors.add(embeddingService.embed(chunk));
        }
        return vectors;
    }

    /**
     * Step 5: 写入 Qdrant
     */
    private void store(List<float[]> vectors, List<String> chunks,
                       Long docId, String title, String sourceType, String tags) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("source_type", sourceType);
            payload.put("source_id", docId);
            payload.put("title", title);
            payload.put("chunk_index", i);
            payload.put("text", chunks.get(i));
            payload.put("tags", tags);
            payloads.add(payload);
        }
        vectorService.upsertVectors(vectors, payloads);
    }
}
