package com.niconicode.knowledge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.common.result.R;
import com.niconicode.knowledge.dto.KnowledgeDocReq;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.service.KnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/docs")
    public R<Page<KnowledgeDoc>> listDocs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Long categoryId) {
        return R.ok(knowledgeService.listDocs(page, size, keyword, tag, categoryId));
    }

    @GetMapping("/docs/{id}")
    public R<KnowledgeDoc> getDoc(@PathVariable Long id) {
        return R.ok(knowledgeService.getDoc(id));
    }

    @GetMapping("/search")
    public R<List<Map<String, Object>>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topK) {
        return R.ok(knowledgeService.semanticSearch(q, topK));
    }

    @PostMapping("/docs")
    public R<KnowledgeDoc> createDoc(@Valid @RequestBody KnowledgeDocReq req) {
        return R.ok(knowledgeService.createDoc(req));
    }

    @PutMapping("/docs/{id}")
    public R<KnowledgeDoc> updateDoc(@PathVariable Long id, @Valid @RequestBody KnowledgeDocReq req) {
        return R.ok(knowledgeService.updateDoc(id, req));
    }

    @DeleteMapping("/docs/{id}")
    public R<Void> deleteDoc(@PathVariable Long id) {
        knowledgeService.deleteDoc(id);
        return R.ok();
    }
}
