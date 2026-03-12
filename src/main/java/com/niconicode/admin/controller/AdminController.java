package com.niconicode.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.admin.dto.TechReportUpdateReq;
import com.niconicode.admin.dto.UserUpdateReq;
import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.TrackerService;
import com.niconicode.auth.entity.User;
import com.niconicode.auth.mapper.UserMapper;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.agent.chat.service.MemoryService;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.common.result.R;
import com.niconicode.errata.dto.ErrataResp;
import com.niconicode.errata.entity.Errata;
import com.niconicode.errata.service.ErrataService;
import com.niconicode.knowledge.dto.KnowledgeDocReq;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.mapper.KnowledgeDocMapper;
import com.niconicode.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TrackerService trackerService;
    private final KnowledgeService knowledgeService;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final ErrataService errataService;
    private final UserMapper userMapper;
    private final MemoryService memoryService;

    // ===== 报道管理 =====

    @GetMapping("/reports")
    public R<Page<TechReport>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long categoryId) {
        return R.ok(trackerService.listAllReports(page, size, status, categoryId));
    }

    @PostMapping("/reports")
    public R<TechReport> createReport(@RequestBody TechReportUpdateReq req) {
        TechReport report = new TechReport();
        report.setTitle(req.getTitle());
        report.setContent(req.getContent());
        report.setCategoryId(req.getCategoryId());
        report.setStatus(req.getStatus() != null ? req.getStatus() : "DRAFT");
        report.setPublishedAt(java.time.LocalDateTime.now());
        return R.ok(trackerService.createReport(report));
    }

    @PutMapping("/reports/{id}")
    public R<TechReport> updateReport(@PathVariable Long id, @RequestBody TechReportUpdateReq req) {
        TechReport updated = new TechReport();
        updated.setTitle(req.getTitle());
        updated.setContent(req.getContent());
        updated.setCategoryId(req.getCategoryId());
        updated.setStatus(req.getStatus());
        return R.ok(trackerService.updateReport(id, updated));
    }

    @DeleteMapping("/reports/{id}")
    public R<Void> deleteReport(@PathVariable Long id) {
        trackerService.deleteReport(id);
        return R.ok();
    }

    // ===== 知识库管理 =====

    @GetMapping("/knowledge")
    public R<Page<KnowledgeDoc>> listKnowledge(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long categoryId) {
        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(categoryId != null, KnowledgeDoc::getCategoryId, categoryId)
                .orderByDesc(KnowledgeDoc::getCreatedAt);
        return R.ok(knowledgeDocMapper.selectPage(new Page<>(page, size), wrapper));
    }

    @PostMapping("/knowledge")
    public R<KnowledgeDoc> createKnowledge(@RequestBody KnowledgeDocReq req) {
        if (req.getSourceType() == null) {
            req.setSourceType("MANUAL");
        }
        return R.ok(knowledgeService.createDoc(req));
    }

    @PutMapping("/knowledge/{id}")
    public R<KnowledgeDoc> updateKnowledge(@PathVariable Long id, @RequestBody KnowledgeDocReq req) {
        return R.ok(knowledgeService.updateDoc(id, req));
    }

    @DeleteMapping("/knowledge/{id}")
    public R<Void> deleteKnowledge(@PathVariable Long id) {
        knowledgeService.deleteDoc(id);
        return R.ok();
    }

    // ===== 勘误管理 =====

    @GetMapping("/errata")
    public R<Page<ErrataResp>> listErrata(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        return R.ok(errataService.listAll(page, size, status));
    }

    @PutMapping("/errata/{id}")
    public R<Errata> reviewErrata(@PathVariable Long id,
                                  @RequestParam String status,
                                  @RequestParam(required = false) String adminNote) {
        return R.ok(errataService.review(id, status, adminNote));
    }

    // ===== 追踪技术管理 =====

    @GetMapping("/techs")
    public R<List<TrackedTech>> listTrackedTechs() {
        return R.ok(trackerService.listTrackedTechs());
    }

    @PostMapping("/techs")
    public R<TrackedTech> createTrackedTech(@RequestBody TrackedTech tech) {
        return R.ok(trackerService.addTrackedTech(tech));
    }

    @PutMapping("/techs/{id}")
    public R<TrackedTech> updateTrackedTech(@PathVariable Long id, @RequestBody TrackedTech req) {
        TrackedTech tech = new TrackedTech();
        tech.setId(id);
        tech.setName(req.getName());
        tech.setCategory(req.getCategory());
        tech.setGithubRepo(req.getGithubRepo());
        tech.setOfficialUrl(req.getOfficialUrl());
        tech.setRssUrl(req.getRssUrl());
        tech.setStatus(req.getStatus());
        return R.ok(trackerService.updateTrackedTech(id, tech));
    }

    @DeleteMapping("/techs/{id}")
    public R<Void> deleteTrackedTech(@PathVariable Long id) {
        trackerService.deleteTrackedTech(id);
        return R.ok();
    }

    // ===== 用户管理 =====

    @GetMapping("/users")
    public R<Page<User>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<User> result = userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt));
        // 清除密码字段
        result.getRecords().forEach(u -> u.setPassword(null));
        return R.ok(result);
    }

    @PutMapping("/users/{id}")
    public R<User> updateUser(@PathVariable Long id, @RequestBody UserUpdateReq req) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException(404, "用户不存在");
        if (req.getRole() != null) user.setRole(req.getRole());
        if (req.getStatus() != null) user.setStatus(req.getStatus());
        userMapper.updateById(user);
        user.setPassword(null);
        return R.ok(user);
    }

    @GetMapping("/users/{id}/sessions")
    public R<List<ChatSession>> getUserSessions(@PathVariable Long id) {
        return R.ok(memoryService.getUserSessions(id));
    }

    @DeleteMapping("/users/{userId}/sessions/{sessionId}")
    public R<Void> deleteUserSession(@PathVariable Long userId, @PathVariable Long sessionId) {
        memoryService.deleteSession(sessionId, userId);
        return R.ok();
    }
}
