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
    private final DocumentETLService etlService;

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

    /**
     * P2-O: 幂等地创建或更新知识文档。
     * 按 sourceType + sourceId 唯一确定一条记录：
     * - 若不存在，则新建（行为与 createDoc 相同）
     * - 若已存在，则更新标题/内容/标签并重新向量化
     *
     * 适用于 Tracker 自动同步场景，防止同一报道被重复写入知识库。
     */
    public KnowledgeDoc createOrUpdateDoc(KnowledgeDocReq req) {
        if (req.getSourceType() != null && req.getSourceId() != null) {
            KnowledgeDoc existing = docMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDoc>()
                            .eq(KnowledgeDoc::getSourceType, req.getSourceType())
                            .eq(KnowledgeDoc::getSourceId, req.getSourceId())
                            .eq(KnowledgeDoc::getStatus, "ACTIVE")
                            .last("LIMIT 1"));
            if (existing != null) {
                // 已存在：更新内容并重新向量化
                existing.setTitle(req.getTitle());
                existing.setContent(req.getContent());
                if (req.getTags() != null) existing.setTags(req.getTags());
                if (req.getCategoryId() != null) existing.setCategoryId(req.getCategoryId());
                docMapper.updateById(existing);
                // 删除旧向量，重新嵌入
                vectorService.deleteBySourceId(existing.getId());
                asyncEmbedAndStore(existing);
                log.debug("Knowledge doc updated (sourceType={}, sourceId={}): id={}",
                        req.getSourceType(), req.getSourceId(), existing.getId());
                return existing;
            }
        }
        // 不存在或无 sourceId：直接新建
        return createDoc(req);
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
        DocumentETLService.ETLResult result = etlService.process(
                doc.getId(), doc.getTitle(), doc.getContent(),
                doc.getSourceType(), doc.getTags());
        if (!result.isSuccess()) {
            log.error("ETL pipeline failed for doc {}: {}", doc.getId(), result.getError());
        }
    }
}
