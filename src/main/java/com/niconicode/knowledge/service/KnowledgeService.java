package com.niconicode.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.knowledge.dto.KnowledgeDocReq;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.mapper.KnowledgeDocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocMapper docMapper;
    private final VectorService vectorService;
    private final EmbeddingService embeddingService;

    public Page<KnowledgeDoc> listDocs(int page, int size, String keyword, String tag) {
        return listDocs(page, size, keyword, tag, null);
    }

    public Page<KnowledgeDoc> listDocs(int page, int size, String keyword, String tag, Long categoryId) {
        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDoc::getStatus, "ACTIVE");
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(KnowledgeDoc::getTitle, keyword)
                    .or().like(KnowledgeDoc::getContent, keyword));
        }
        if (tag != null && !tag.isBlank()) {
            wrapper.like(KnowledgeDoc::getTags, tag);
        }
        if (categoryId != null) {
            wrapper.eq(KnowledgeDoc::getCategoryId, categoryId);
        }
        wrapper.orderByDesc(KnowledgeDoc::getCreatedAt);
        return docMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public KnowledgeDoc getDoc(Long id) {
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc == null) throw new BusinessException(404, "文档不存在");
        // 增加浏览量
        doc.setViewCount(doc.getViewCount() + 1);
        docMapper.updateById(doc);
        return doc;
    }

    public KnowledgeDoc createDoc(KnowledgeDocReq req) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(req.getTitle());
        doc.setContent(req.getContent());
        doc.setSourceType(req.getSourceType() != null ? req.getSourceType() : "MANUAL");
        doc.setSourceId(req.getSourceId());
        doc.setTags(req.getTags());
        doc.setCategoryId(req.getCategoryId());
        doc.setViewCount(0);
        doc.setStatus("ACTIVE");
        docMapper.insert(doc);

        // 异步向量化
        asyncEmbedAndStore(doc);
        return doc;
    }

    public KnowledgeDoc updateDoc(Long id, KnowledgeDocReq req) {
        KnowledgeDoc doc = docMapper.selectById(id);
        if (doc == null) throw new BusinessException(404, "文档不存在");
        doc.setTitle(req.getTitle());
        doc.setContent(req.getContent());
        doc.setTags(req.getTags());
        doc.setCategoryId(req.getCategoryId());
        docMapper.updateById(doc);

        // 重新向量化
        vectorService.deleteBySourceId(id);
        asyncEmbedAndStore(doc);
        return doc;
    }

    public void deleteDoc(Long id) {
        docMapper.deleteById(id);
        vectorService.deleteBySourceId(id);
    }

    public List<Map<String, Object>> semanticSearch(String query, int topK) {
        try {
            float[] queryVec = embeddingService.embed(query);
            return vectorService.search(queryVec, topK);
        } catch (Exception e) {
            log.error("Semantic search failed", e);
            return Collections.emptyList();
        }
    }

    @Async
    public void asyncEmbedAndStore(KnowledgeDoc doc) {
        try {
            // 分片（按段落，简单策略）
            List<String> chunks = splitContent(doc.getContent(), 800);
            List<float[]> vectors = new ArrayList<>();
            List<Map<String, Object>> payloads = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
                float[] vec = embeddingService.embed(chunks.get(i));
                vectors.add(vec);
                Map<String, Object> payload = new HashMap<>();
                payload.put("source_type", doc.getSourceType());
                payload.put("source_id", doc.getId());
                payload.put("title", doc.getTitle());
                payload.put("chunk_index", i);
                payload.put("text", chunks.get(i));
                payload.put("tags", doc.getTags());
                payloads.add(payload);
            }

            vectorService.upsertVectors(vectors, payloads);
            log.info("Embedded and stored {} chunks for doc {}", chunks.size(), doc.getId());
        } catch (Exception e) {
            log.error("Failed to embed doc {}", doc.getId(), e);
        }
    }

    private List<String> splitContent(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\n\n+");
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
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
}
