package com.niconicode.agent.chat.tool;

import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.TrackerService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TechTrackerTools {

    private final TrackerService trackerService;

    @Tool("按关键词搜索已发布的技术报道")
    public String searchReports(@P("搜索关键词") String keyword) {
        log.info("Tool call: searchReports({})", keyword);
        List<TechReport> reports = trackerService.searchReports(keyword, 5);
        if (reports.isEmpty()) {
            return "未找到与「" + keyword + "」相关的报道";
        }
        return reports.stream()
                .map(r -> "【" + r.getTitle() + "】\n" + (r.getChangeSummary() != null ? r.getChangeSummary() : ""))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool("获取指定技术的版本和追踪状态")
    public String getTechInfo(@P("技术名称") String techName) {
        log.info("Tool call: getTechInfo({})", techName);
        TrackedTech tech = trackerService.findTechByName(techName);
        if (tech == null) {
            return "未找到名为「" + techName + "」的追踪技术";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("技术: ").append(tech.getName()).append("\n");
        sb.append("分类: ").append(tech.getCategory()).append("\n");
        sb.append("最新版本: ").append(tech.getLastKnownVersion() != null ? tech.getLastKnownVersion() : "未知").append("\n");
        sb.append("追踪模式: ").append(tech.getTrackingMode() != null ? tech.getTrackingMode() : "RELEASE").append("\n");
        sb.append("状态: ").append(tech.getStatus()).append("\n");
        if (tech.getGithubRepo() != null) {
            sb.append("GitHub: https://github.com/").append(tech.getGithubRepo()).append("\n");
        }
        if (tech.getLastCheckedAt() != null) {
            sb.append("最后检查: ").append(tech.getLastCheckedAt()).append("\n");
        }
        return sb.toString();
    }

    @Tool("列出所有正在追踪的技术")
    public String listTrackedTechnologies() {
        log.info("Tool call: listTrackedTechnologies()");
        List<TrackedTech> techs = trackerService.listTrackedTechs();
        if (techs.isEmpty()) {
            return "当前没有追踪中的技术";
        }
        return techs.stream()
                .filter(t -> "ACTIVE".equals(t.getStatus()))
                .map(t -> "- " + t.getName() + " (" + t.getCategory() + ")"
                        + (t.getLastKnownVersion() != null ? " v" + t.getLastKnownVersion() : ""))
                .collect(Collectors.joining("\n"));
    }

    @Tool("获取指定技术最近的报道列表")
    public String getRecentReportsForTech(@P("技术名称") String techName) {
        log.info("Tool call: getRecentReportsForTech({})", techName);
        TrackedTech tech = trackerService.findTechByName(techName);
        if (tech == null) {
            return "未找到名为「" + techName + "」的追踪技术";
        }
        var page = trackerService.getReportsByTechId(tech.getId(), 1, 5);
        List<TechReport> reports = page.getRecords();
        if (reports.isEmpty()) {
            return tech.getName() + " 目前没有已发布的报道";
        }
        return reports.stream()
                .map(r -> "【" + r.getTitle() + "】(" + r.getPublishedAt() + ")\n"
                        + (r.getChangeSummary() != null ? r.getChangeSummary() : ""))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool("记录用户提到的技术关键词，用于热点话题追踪。当用户在对话中讨论某个技术时调用此方法。")
    public String recordTechMention(@P("技术名称关键词") String techName) {
        log.info("Tool call: recordTechMention({})", techName);
        try {
            trackerService.recordMention(techName);
            return "已记录技术提及: " + techName;
        } catch (Exception e) {
            log.warn("Failed to record tech mention: {}", techName, e);
            return "记录失败";
        }
    }
}
