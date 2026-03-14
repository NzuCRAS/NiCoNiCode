package com.niconicode.agent.tracker.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.TrackerService;
import com.niconicode.common.result.R;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tracker")
@RequiredArgsConstructor
public class TrackerController {

    private final TrackerService trackerService;

    @GetMapping("/reports")
    public R<Page<TechReport>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long categoryId) {
        return R.ok(trackerService.listReports(page, size, categoryId));
    }

    @GetMapping("/reports/by-score")
    public R<Page<TechReport>> listReportsByScore(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long categoryId) {
        return R.ok(trackerService.listReportsByScore(page, size, categoryId));
    }

    @GetMapping("/reports/{id}")
    public R<TechReport> getReport(@PathVariable Long id) {
        return R.ok(trackerService.getReport(id));
    }

    @GetMapping("/reports/latest")
    public R<List<TechReport>> latestReports(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(trackerService.getLatestReports(limit));
    }

    @GetMapping("/techs")
    public R<List<TrackedTech>> listTechs() {
        return R.ok(trackerService.listTrackedTechs());
    }

    @GetMapping("/techs/{id}")
    public R<TrackedTech> getTech(@PathVariable Long id) {
        return R.ok(trackerService.getTrackedTech(id));
    }

    @GetMapping("/techs/{id}/reports")
    public R<Page<TechReport>> getTechReports(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(trackerService.getReportsByTechId(id, page, size));
    }

    @GetMapping("/techs/{id}/knowledge")
    public R<List<KnowledgeDoc>> getTechKnowledge(@PathVariable Long id) {
        return R.ok(trackerService.getKnowledgeByTechId(id));
    }

    @PostMapping("/techs")
    public R<TrackedTech> addTech(@RequestBody TrackedTech tech) {
        return R.ok(trackerService.addTrackedTech(tech));
    }

    @DeleteMapping("/techs/{id}")
    public R<Void> deleteTech(@PathVariable Long id) {
        trackerService.deleteTrackedTech(id);
        return R.ok();
    }

    @PostMapping("/check-now/{id}")
    public R<Void> checkNow(@PathVariable Long id) {
        trackerService.checkTechUpdate(id);
        return R.ok();
    }

    @PostMapping("/reports/{id}/save-to-knowledge")
    public R<Void> saveReportAsKnowledge(
            @PathVariable Long id,
            @RequestParam(required = false) Long categoryId) {
        trackerService.saveReportAsKnowledge(id, categoryId);
        return R.ok();
    }
}
