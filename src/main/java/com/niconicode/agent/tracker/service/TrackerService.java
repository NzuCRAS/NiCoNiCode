package com.niconicode.agent.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import com.niconicode.agent.tracker.entity.HotTopic;
import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.mapper.HotTopicMapper;
import com.niconicode.agent.tracker.mapper.TechReportMapper;
import com.niconicode.agent.tracker.mapper.TrackedTechMapper;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.knowledge.dto.KnowledgeDocReq;
import com.niconicode.knowledge.service.KnowledgeService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackerService {

    private final TrackedTechMapper techMapper;
    private final TechReportMapper reportMapper;
    private final HotTopicMapper hotTopicMapper;
    private final GitHubMonitorService githubMonitor;
    private final KnowledgeService knowledgeService;
    private final ChatLanguageModel chatModel;

    public Page<TechReport> listReports(int page, int size, Long categoryId) {
        LambdaQueryWrapper<TechReport> wrapper = new LambdaQueryWrapper<TechReport>()
                .eq(TechReport::getStatus, "PUBLISHED")
                .eq(categoryId != null, TechReport::getCategoryId, categoryId)
                .orderByDesc(TechReport::getPublishedAt);
        return reportMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public Page<TechReport> listAllReports(int page, int size, String status, Long categoryId) {
        LambdaQueryWrapper<TechReport> wrapper = new LambdaQueryWrapper<TechReport>()
                .eq(status != null && !status.isBlank(), TechReport::getStatus, status)
                .eq(categoryId != null, TechReport::getCategoryId, categoryId)
                .orderByDesc(TechReport::getCreatedAt);
        return reportMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public TechReport getReport(Long id) {
        TechReport report = reportMapper.selectById(id);
        if (report == null) throw new BusinessException(404, "报道不存在");
        return report;
    }

    public List<TechReport> getLatestReports(int limit) {
        return reportMapper.selectList(
                new LambdaQueryWrapper<TechReport>()
                        .eq(TechReport::getStatus, "PUBLISHED")
                        .orderByDesc(TechReport::getPublishedAt)
                        .last("LIMIT " + limit)
        );
    }

    public TechReport updateReport(Long id, TechReport updated) {
        TechReport report = reportMapper.selectById(id);
        if (report == null) throw new BusinessException(404, "报道不存在");
        if (updated.getTitle() != null) report.setTitle(updated.getTitle());
        if (updated.getContent() != null) report.setContent(updated.getContent());
        if (updated.getCategoryId() != null) report.setCategoryId(updated.getCategoryId());
        if (updated.getStatus() != null) report.setStatus(updated.getStatus());
        reportMapper.updateById(report);
        return report;
    }

    public void deleteReport(Long id) {
        reportMapper.deleteById(id);
    }

    public List<TrackedTech> listTrackedTechs() {
        return techMapper.selectList(
                new LambdaQueryWrapper<TrackedTech>().orderByDesc(TrackedTech::getMentionCount));
    }

    public TrackedTech addTrackedTech(TrackedTech tech) {
        tech.setStatus("ACTIVE");
        tech.setMentionCount(0);
        tech.setIsHot(false);
        techMapper.insert(tech);
        return tech;
    }

    public void deleteTrackedTech(Long id) {
        techMapper.deleteById(id);
    }

    /**
     * 检查单个技术的更新
     */
    public void checkTechUpdate(Long techId) {
        TrackedTech tech = techMapper.selectById(techId);
        if (tech == null || !"ACTIVE".equals(tech.getStatus())) return;

        // 检查 GitHub Release
        if (tech.getGithubRepo() != null && !tech.getGithubRepo().isBlank()) {
            String newVersion = githubMonitor.checkLatestRelease(tech.getGithubRepo(), tech.getLastKnownVersion());
            if (newVersion != null) {
                log.info("New version detected for {}: {}", tech.getName(), newVersion);
                GitHubReleaseInfo releaseInfo = githubMonitor.getFullReleaseInfo(tech.getGithubRepo(), newVersion);
                generateReport(tech, newVersion, releaseInfo);
                tech.setLastKnownVersion(newVersion);
            }
        }

        tech.setLastCheckedAt(LocalDateTime.now());
        techMapper.updateById(tech);
    }

    private void generateReport(TrackedTech tech, String newVersion, GitHubReleaseInfo releaseInfo) {
        String releaseNotes = releaseInfo.getBody() != null ? releaseInfo.getBody() : "";
        String publishedAt = releaseInfo.getPublishedAt() != null ? releaseInfo.getPublishedAt() : "";
        String releaseUrl = releaseInfo.getHtmlUrl() != null ? releaseInfo.getHtmlUrl() : "";

        String prompt = """
                你是一位资深技术新闻记者。请根据以下信息，用中文写一篇专业的技术更新报道（Markdown格式）。

                技术: %s
                类别: %s
                新版本: %s
                发布日期: %s
                Release 页面: %s
                Release Notes:
                %s

                要求：
                1. 标题要有吸引力，体现版本号和核心亮点
                2. 文章结构清晰，分为以下几个部分：
                   - **版本亮点**：概述 2-3 个最重要的新特性
                   - **更新内容详解**：详细说明主要变更
                   - **对开发者的影响**：分析这些变更对现有用户的意义
                   - **升级建议**：给出升级注意事项
                   - **来源链接**：附上 Release 页面链接
                3. 内容充实，800-1500字
                4. 直接输出 Markdown 内容，不要用 ```markdown ``` 包裹
                """.formatted(
                tech.getName(), tech.getCategory(), newVersion, publishedAt, releaseUrl,
                releaseNotes.length() > 3000 ? releaseNotes.substring(0, 3000) : releaseNotes);

        try {
            String reportContent = chatModel.chat(prompt);
            reportContent = cleanMarkdownContent(reportContent);

            TechReport report = new TechReport();
            report.setTrackedTechId(tech.getId());
            report.setTitle(tech.getName() + " " + newVersion + " 发布");
            report.setContent(reportContent);
            report.setNewVersion(newVersion);
            report.setChangeSummary(releaseNotes.length() > 500 ? releaseNotes.substring(0, 500) : releaseNotes);
            report.setSourceUrls("[\"" + releaseUrl.replace("\"", "\\\"") + "\"]");
            report.setStatus("PUBLISHED");
            report.setPublishedAt(LocalDateTime.now());
            reportMapper.insert(report);

            // 同步到知识库
            KnowledgeDocReq docReq = new KnowledgeDocReq();
            docReq.setTitle(report.getTitle());
            docReq.setContent(reportContent);
            docReq.setSourceType("TRACKER_REPORT");
            docReq.setSourceId(report.getId());
            docReq.setTags(tech.getName() + "," + tech.getCategory());
            knowledgeService.createDoc(docReq);

            log.info("Generated report for {} {}", tech.getName(), newVersion);
        } catch (Exception e) {
            log.error("Failed to generate report for {}", tech.getName(), e);
        }
    }

    /**
     * 清洗 AI 返回内容中的外层 Markdown 代码块包裹
     */
    private String cleanMarkdownContent(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.startsWith("```markdown") || trimmed.startsWith("```md")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0 && trimmed.endsWith("```")) {
                trimmed = trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        } else if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length() > 6) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    /**
     * 记录热点话题
     */
    public void recordMention(String keyword) {
        HotTopic topic = hotTopicMapper.selectOne(
                new LambdaQueryWrapper<HotTopic>().eq(HotTopic::getKeyword, keyword));
        if (topic == null) {
            topic = new HotTopic();
            topic.setKeyword(keyword);
            topic.setMentionCount(1);
            topic.setLastMentionedAt(LocalDateTime.now());
            topic.setPromotedToTracked(false);
            hotTopicMapper.insert(topic);
        } else {
            topic.setMentionCount(topic.getMentionCount() + 1);
            topic.setLastMentionedAt(LocalDateTime.now());
            hotTopicMapper.updateById(topic);
        }
    }
}
