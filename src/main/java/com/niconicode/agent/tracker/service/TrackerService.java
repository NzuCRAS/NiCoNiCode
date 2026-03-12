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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * 按技术指数 + 时间指数的综合评分排序（用于重要展示）
     * 综合评分 = techIndex * 0.6 + timeIndex * 0.4
     * 时间指数：总分 840，每小时跌 5 分
     *
     * 优化：在程序中排序，而非SQL中计算
     * - 分批获取数据（避免一次加载过多）
     * - 在内存中计算综合评分
     * - 使用高效的排序算法（Java TimSort）
     * - 避免占用数据库缓存
     */
    public Page<TechReport> listReportsByScore(int page, int size, Long categoryId) {
        // 第一步：获取一个足够大的结果集，按发布时间降序（减少排序工作量）
        // 获取 page*size + buffer 的数据，以支持重排后获取正确的分页结果
        int fetchSize = Math.max(size * page + 100, 500); // 至少获取 500 条或 page*size+100

        LambdaQueryWrapper<TechReport> wrapper = new LambdaQueryWrapper<TechReport>()
                .eq(TechReport::getStatus, "PUBLISHED")
                .eq(categoryId != null, TechReport::getCategoryId, categoryId)
                .orderByDesc(TechReport::getPublishedAt);

        Page<TechReport> tempPage = reportMapper.selectPage(
                new Page<>(1, fetchSize), wrapper);

        List<TechReport> reports = tempPage.getRecords();

        // 第二步：为每个报道计算综合评分
        List<ReportScore> reportScores = new ArrayList<>();
        for (TechReport report : reports) {
            int timeIndex = calculateTimeIndex(report.getPublishedAt());
            int techIndex = report.getTechIndex() != null ? report.getTechIndex() : 500;
            double score = techIndex * 0.6 + timeIndex * 0.4;
            reportScores.add(new ReportScore(report, score));
        }

        // 第三步：使用高效的排序算法（Java 内置 TimSort）按综合评分排序
        reportScores.sort((a, b) -> Double.compare(b.score, a.score));

        // 第四步：分页返回结果
        Page<TechReport> result = new Page<>(page, size, tempPage.getTotal());
        int start = (page - 1) * size;
        int end = Math.min(start + size, reportScores.size());

        List<TechReport> pageRecords = reportScores.stream()
                .skip(start)
                .limit(size)
                .map(rs -> rs.report)
                .collect(Collectors.toList());

        result.setRecords(pageRecords);
        return result;
    }

    /**
     * 内部类：用于排序的报道评分信息
     */
    private static class ReportScore {
        TechReport report;
        double score;

        ReportScore(TechReport report, double score) {
            this.report = report;
            this.score = score;
        }
    }

    /**
     * 计算时间指数 (0-840)
     * 规则：总分 840，刚发布时满分，每小时跌 5 分
     */
    public int calculateTimeIndex(LocalDateTime publishedAt) {
        if (publishedAt == null) return 0;

        long hours = java.time.temporal.ChronoUnit.HOURS.between(publishedAt, LocalDateTime.now());
        int timeIndex = 840 - (int)(hours * 5);

        return Math.max(0, timeIndex);
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

    public TechReport createReport(TechReport report) {
        if (report.getStatus() == null) {
            report.setStatus("DRAFT");
        }
        reportMapper.insert(report);
        return report;
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

    public TrackedTech updateTrackedTech(Long id, TrackedTech tech) {
        TrackedTech existing = techMapper.selectById(id);
        if (existing == null) throw new BusinessException(404, "技术不存在");
        if (tech.getName() != null) existing.setName(tech.getName());
        if (tech.getCategory() != null) existing.setCategory(tech.getCategory());
        if (tech.getGithubRepo() != null) existing.setGithubRepo(tech.getGithubRepo());
        if (tech.getOfficialUrl() != null) existing.setOfficialUrl(tech.getOfficialUrl());
        if (tech.getRssUrl() != null) existing.setRssUrl(tech.getRssUrl());
        if (tech.getStatus() != null) existing.setStatus(tech.getStatus());
        techMapper.updateById(existing);
        return existing;
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
                   - **更新内容详解**：详细说明主要变更，把每个地方都讲清楚
                   - **对开发者的影响**：分析这些变更对现有用户的意义
                   - **升级建议**：给出升级注意事项
                   - **来源链接**：附上 Release 页面链接
                3. 要求内容充实完整，不要硬性限制字数，重点是讲清楚每处更新
                4. 直接输出 Markdown 内容，不要用 ```markdown ``` 包裹
                """.formatted(
                tech.getName(), tech.getCategory(), newVersion, publishedAt, releaseUrl,
                releaseNotes.length() > 3000 ? releaseNotes.substring(0, 3000) : releaseNotes);

        try {
            String reportContent = chatModel.chat(prompt);
            reportContent = cleanMarkdownContent(reportContent);

            // 生成技术指数
            int techIndex = generateTechIndex(tech.getName(), tech.getCategory(), reportContent);

            TechReport report = new TechReport();
            report.setTrackedTechId(tech.getId());
            report.setTitle(tech.getName() + " " + newVersion + " 发布");
            report.setContent(reportContent);
            report.setNewVersion(newVersion);
            report.setChangeSummary(releaseNotes.length() > 500 ? releaseNotes.substring(0, 500) : releaseNotes);
            report.setSourceUrls("[\"" + releaseUrl.replace("\"", "\\\"") + "\"]");
            report.setStatus("PUBLISHED");
            report.setTechIndex(techIndex);
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

            log.info("Generated report for {} {} with tech index {}", tech.getName(), newVersion, techIndex);
        } catch (Exception e) {
            log.error("Failed to generate report for {}", tech.getName(), e);
        }
    }

    /**
     * 根据技术名称、分类和报道内容生成技术指数 (0-1000)
     * 指数范围：
     * - 0: 无人在意的小型开源项目的一次 README 更新
     * - 500: 知名技术框架或工具的常规更新
     * - 1000: 最前沿的大模型等顶级技术的版本迭代
     */
    private int generateTechIndex(String techName, String category, String content) {
        String prompt = """
                根据以下技术名称、分类和报道内容，生成一个 0-1000 的技术指数，用于评估这次更新的质量和影响因子。

                评分标准：
                - 0-100：无人在意的小型开源项目的一次 README 或小补丁更新
                - 100-300：小型或不知名项目的常规更新
                - 300-500：知名开源框架或工具的常规更新
                - 500-700：重要技术框架（如 Spring、React、Vue 等）的中等更新
                - 700-900：影响广泛的重要技术（如 Node.js、Docker 等）的重要更新
                - 900-1000：最前沿的大模型（如 Claude、GPT 等）或操作系统级别的重要版本迭代

                技术名称: %s
                分类: %s
                报道内容摘要（前500字）:
                %s

                请直接返回一个数字 (0-1000)，不要其他说明文字。
                """.formatted(
                techName, category,
                content.length() > 500 ? content.substring(0, 500) : content);

        try {
            String response = chatModel.chat(prompt).trim();
            return Math.max(0, Math.min(1000, Integer.parseInt(response.replaceAll("[^0-9]", ""))));
        } catch (Exception e) {
            log.warn("Failed to generate tech index for {}, using default 500", techName, e);
            return 500;
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
     * 将技术报道保存为知识文档，自动推荐分类和标签
     */
    public void saveReportAsKnowledge(Long reportId, Long categoryId) {
        TechReport report = reportMapper.selectById(reportId);
        if (report == null) throw new BusinessException(404, "报道不存在");

        // 检查是否已保存（避免重复）
        // 注：这里需要检查 knowledge_doc 表中是否已有相同的 sourceId 和 sourceType

        // 生成 tags 和自动推荐分类
        String tagsPrompt = """
                根据以下技术报道的标题和内容，生成 3-5 个逗号分隔的标签（中文或英文），要求：
                1. 标签应该体现核心技术和更新内容
                2. 避免过于通用的标签
                3. 直接返回标签列表，不要其他说明文字

                标题: %s
                内容摘要: %s
                """.formatted(report.getTitle(), report.getContent().substring(0, Math.min(500, report.getContent().length())));

        String tagsResponse = chatModel.chat(tagsPrompt).trim();
        String tags = tagsResponse.replaceAll("[\\n\\r]+", ",").replaceAll(",+", ",").replaceAll("^,|,$", "");

        // 创建知识文档
        KnowledgeDocReq docReq = new KnowledgeDocReq();
        docReq.setTitle(report.getTitle());
        docReq.setContent(report.getContent());
        docReq.setSourceType("TRACKER_REPORT");
        docReq.setSourceId(report.getId());
        docReq.setTags(tags);
        docReq.setCategoryId(categoryId);

        try {
            knowledgeService.createDoc(docReq);
            log.info("Saved tech report {} as knowledge doc with tags: {}", reportId, tags);
        } catch (Exception e) {
            log.error("Failed to save tech report {} as knowledge doc", reportId, e);
            throw new BusinessException(500, "保存知识文档失败");
        }
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
