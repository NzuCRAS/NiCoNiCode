package com.niconicode.agent.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import com.niconicode.agent.tracker.entity.HotTopic;
import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.mapper.HotTopicMapper;
import com.niconicode.agent.tracker.mapper.TechReportMapper;
import com.niconicode.agent.tracker.mapper.TrackedTechMapper;
import com.niconicode.agent.tracker.agent.SearchAgent;
import com.niconicode.agent.tracker.agent.WriterAgent;
import com.niconicode.agent.tracker.agent.ReviewerAgent;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.knowledge.dto.KnowledgeDocReq;
import com.niconicode.knowledge.entity.KnowledgeDoc;
import com.niconicode.knowledge.mapper.KnowledgeDocMapper;
import com.niconicode.knowledge.service.KnowledgeService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final GitHubMonitorService githubMonitor;
    private final KnowledgeService knowledgeService;
    private final ChatLanguageModel chatModel;
    private final SearchAgent searchAgent;
    private final WriterAgent writerAgent;
    private final ReviewerAgent reviewerAgent;

    @Value("${ai.hot-topic.promotion-threshold:10}")
    private int promotionThreshold;

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
        if (updated.getTrackedTechId() != null) report.setTrackedTechId(updated.getTrackedTechId());
        if (updated.getTechIndex() != null) report.setTechIndex(updated.getTechIndex());
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
        if (tech.getTrackingMode() != null) existing.setTrackingMode(tech.getTrackingMode());
        if (tech.getStatus() != null) existing.setStatus(tech.getStatus());
        techMapper.updateById(existing);
        return existing;
    }

    /**
     * 检查单个技术的更新 — Multi-Agent 流水线
     * SearchAgent → WriterAgent → ReviewerAgent → 发布
     */
    public void checkTechUpdate(Long techId) {
        TrackedTech tech = techMapper.selectById(techId);
        if (tech == null || !"ACTIVE".equals(tech.getStatus())) return;

        if (tech.getGithubRepo() == null || tech.getGithubRepo().isBlank()) return;

        try {
            // Stage 1: SearchAgent — 多渠道信息搜集
            SearchAgent.SearchResult searchResult = searchAgent.search(tech);
            if (!searchResult.isHasUpdate()) {
                log.debug("No update detected for {}", tech.getName());
                tech.setLastCheckedAt(LocalDateTime.now());
                techMapper.updateById(tech);
                return;
            }

            log.info("Update detected for {} ({} mode): {}",
                    tech.getName(), searchResult.getUpdateMode(), searchResult.getDetectedVersion());

            // Stage 2: WriterAgent — 撰写高质量报道
            String reportContent = writerAgent.write(tech, searchResult);

            // Stage 3: ReviewerAgent — 审核 + 评分 + 发布决策
            ReviewerAgent.ReviewResult review = reviewerAgent.review(tech, reportContent, searchResult);

            if (review.isApproved()) {
                // 构建并发布报道
                TechReport report = new TechReport();
                report.setTrackedTechId(tech.getId());
                report.setTitle(tech.getName() + " " + searchResult.getDetectedVersion() + " "
                        + getModeLabel(searchResult.getUpdateMode()));
                report.setContent(review.getRevisedContent());
                report.setNewVersion(searchResult.getDetectedVersion());
                report.setChangeSummary(
                        searchResult.getRawInfoSummary() != null
                                ? searchResult.getRawInfoSummary().substring(0,
                                    Math.min(500, searchResult.getRawInfoSummary().length()))
                                : "");
                report.setSourceUrls(buildSourceUrlsJson(searchResult));
                report.setStatus("PUBLISHED");
                report.setTechIndex(review.getTechIndex());
                report.setPublishedAt(LocalDateTime.now());
                reportMapper.insert(report);

                // 同步到知识库
                syncToKnowledge(report, tech);

                log.info("Published report for {} {} (tech index: {}, quality: {})",
                        tech.getName(), searchResult.getDetectedVersion(),
                        review.getTechIndex(), review.getQualityNotes());
            } else {
                log.info("Report for {} rejected by ReviewerAgent: {}",
                        tech.getName(), review.getRejectionReason());
            }

            // 更新追踪状态
            updateTrackingState(tech, searchResult);

        } catch (Exception e) {
            log.error("Multi-agent pipeline failed for {}", tech.getName(), e);
        }

        tech.setLastCheckedAt(LocalDateTime.now());
        techMapper.updateById(tech);
    }

    private String getModeLabel(String mode) {
        return switch (mode) {
            case "RELEASE" -> "发布";
            case "TAG" -> "发布 (Tag)";
            case "COMMIT" -> "开发动态";
            default -> "更新";
        };
    }

    private String buildSourceUrlsJson(SearchAgent.SearchResult searchResult) {
        if (searchResult.getSourceUrls().isEmpty()) return "[]";
        return searchResult.getSourceUrls().stream()
                .map(url -> "\"" + url.replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private void syncToKnowledge(TechReport report, TrackedTech tech) {
        try {
            KnowledgeDocReq docReq = new KnowledgeDocReq();
            docReq.setTitle(report.getTitle());
            docReq.setContent(report.getContent());
            docReq.setSourceType("TRACKER_REPORT");
            docReq.setSourceId(report.getId());
            docReq.setTags(tech.getName() + "," + tech.getCategory());
            knowledgeService.createDoc(docReq);
        } catch (Exception e) {
            log.warn("Failed to sync report to knowledge base: {}", e.getMessage());
        }
    }

    private void updateTrackingState(TrackedTech tech, SearchAgent.SearchResult searchResult) {
        if ("RELEASE".equals(searchResult.getUpdateMode())
                || "TAG".equals(searchResult.getUpdateMode())) {
            tech.setLastKnownVersion(searchResult.getDetectedVersion());
        }
        if ("TAG".equals(searchResult.getUpdateMode())
                && "RELEASE".equals(tech.getTrackingMode())) {
            // Release 降级为 Tag
            tech.setTrackingMode("TAG");
        }
        if ("COMMIT".equals(searchResult.getUpdateMode())
                && !searchResult.getRecentCommits().isEmpty()) {
            tech.setLastKnownCommitSha(searchResult.getRecentCommits().get(0).getSha());
        }
    }

    /**
     * 根据技术名称、分类和报道内容生成技术指数 (0-1000)
     * @deprecated 已被 ReviewerAgent.scoreTechIndex() 替代，保留用于手动创建报道
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
     * 记录热点话题，同时更新已追踪技术的提及计数
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

        // 同步更新已追踪技术的 mentionCount
        TrackedTech trackedTech = techMapper.selectOne(
                new LambdaQueryWrapper<TrackedTech>()
                        .like(TrackedTech::getName, keyword)
                        .eq(TrackedTech::getStatus, "ACTIVE")
                        .last("LIMIT 1"));
        if (trackedTech != null) {
            trackedTech.setMentionCount(
                    (trackedTech.getMentionCount() != null ? trackedTech.getMentionCount() : 0) + 1);
            techMapper.updateById(trackedTech);
        }

        // 检查是否需要自动晋级
        checkAndPromote(topic);
    }

    /**
     * 检查热点话题是否达到晋级阈值，自动创建追踪技术
     */
    private void checkAndPromote(HotTopic topic) {
        if (topic.getPromotedToTracked() != null && topic.getPromotedToTracked()) {
            return;
        }
        if (topic.getMentionCount() < promotionThreshold) {
            return;
        }

        // 检查是否已有同名追踪技术
        TrackedTech existing = techMapper.selectOne(
                new LambdaQueryWrapper<TrackedTech>()
                        .eq(TrackedTech::getName, topic.getKeyword()));
        if (existing != null) {
            topic.setPromotedToTracked(true);
            hotTopicMapper.updateById(topic);
            return;
        }

        // 创建新追踪技术
        TrackedTech newTech = new TrackedTech();
        newTech.setName(topic.getKeyword());
        newTech.setCategory("其他");
        newTech.setStatus("ACTIVE");
        newTech.setMentionCount(topic.getMentionCount());
        newTech.setIsHot(true);
        techMapper.insert(newTech);

        topic.setPromotedToTracked(true);
        hotTopicMapper.updateById(topic);

        log.info("Hot topic '{}' promoted to tracked tech (mentions: {})", topic.getKeyword(), topic.getMentionCount());
    }

    /**
     * 获取所有追踪技术名称列表（用于关键词匹配）
     */
    public List<String> getAllTechNames() {
        return techMapper.selectList(
                new LambdaQueryWrapper<TrackedTech>()
                        .eq(TrackedTech::getStatus, "ACTIVE")
                        .select(TrackedTech::getName)
        ).stream().map(TrackedTech::getName).collect(Collectors.toList());
    }

    /**
     * 获取单个追踪技术
     */
    public TrackedTech getTrackedTech(Long id) {
        TrackedTech tech = techMapper.selectById(id);
        if (tech == null) throw new BusinessException(404, "技术不存在");
        return tech;
    }

    /**
     * 按技术ID获取已发布的报道列表
     */
    public Page<TechReport> getReportsByTechId(Long techId, int page, int size) {
        LambdaQueryWrapper<TechReport> wrapper = new LambdaQueryWrapper<TechReport>()
                .eq(TechReport::getTrackedTechId, techId)
                .eq(TechReport::getStatus, "PUBLISHED")
                .orderByDesc(TechReport::getPublishedAt);
        return reportMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 按关键词搜索已发布报道（title/content LIKE）
     */
    public List<TechReport> searchReports(String keyword, int limit) {
        return reportMapper.selectList(
                new LambdaQueryWrapper<TechReport>()
                        .eq(TechReport::getStatus, "PUBLISHED")
                        .and(w -> w.like(TechReport::getTitle, keyword)
                                .or()
                                .like(TechReport::getContent, keyword))
                        .orderByDesc(TechReport::getPublishedAt)
                        .last("LIMIT " + limit)
        );
    }

    /**
     * 按名称模糊匹配追踪技术
     */
    public TrackedTech findTechByName(String name) {
        return techMapper.selectOne(
                new LambdaQueryWrapper<TrackedTech>()
                        .like(TrackedTech::getName, name)
                        .eq(TrackedTech::getStatus, "ACTIVE")
                        .last("LIMIT 1")
        );
    }

    /**
     * 按技术ID获取关联的知识文档
     * 双通道：1) sourceType=TRACKER_REPORT 且 sourceId 对应该技术的报道
     *         2) tags 包含技术名称
     */
    public List<KnowledgeDoc> getKnowledgeByTechId(Long techId) {
        TrackedTech tech = techMapper.selectById(techId);
        if (tech == null) return List.of();

        // 获取该技术所有报道的 ID
        List<TechReport> reports = reportMapper.selectList(
                new LambdaQueryWrapper<TechReport>()
                        .eq(TechReport::getTrackedTechId, techId)
                        .select(TechReport::getId)
        );
        List<Long> reportIds = reports.stream().map(TechReport::getId).collect(Collectors.toList());

        // 通道1: 通过 sourceId 关联
        List<KnowledgeDoc> docs = new ArrayList<>();
        if (!reportIds.isEmpty()) {
            docs.addAll(knowledgeDocMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDoc>()
                            .eq(KnowledgeDoc::getSourceType, "TRACKER_REPORT")
                            .in(KnowledgeDoc::getSourceId, reportIds)
                            .eq(KnowledgeDoc::getStatus, "ACTIVE")
            ));
        }

        // 通道2: 通过 tags 模糊匹配技术名称
        List<KnowledgeDoc> tagDocs = knowledgeDocMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDoc>()
                        .like(KnowledgeDoc::getTags, tech.getName())
                        .eq(KnowledgeDoc::getStatus, "ACTIVE")
        );

        // 合并去重
        for (KnowledgeDoc doc : tagDocs) {
            if (docs.stream().noneMatch(d -> d.getId().equals(doc.getId()))) {
                docs.add(doc);
            }
        }

        return docs;
    }
}
