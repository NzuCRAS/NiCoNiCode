package com.niconicode.agent.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import com.niconicode.agent.tracker.dto.TrackerGraphResp;
import com.niconicode.agent.tracker.dto.TrackerNetworkResp;
import com.niconicode.agent.tracker.entity.HotTopic;
import com.niconicode.agent.tracker.entity.TechReport;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.GitHubSearchService;
import com.niconicode.agent.tracker.mapper.HotTopicMapper;
import com.niconicode.agent.tracker.mapper.TechReportMapper;
import com.niconicode.agent.tracker.mapper.TrackedTechMapper;
import com.niconicode.agent.tracker.agent.SearchAgent;
import com.niconicode.agent.tracker.agent.WriterAgent;
import com.niconicode.agent.tracker.agent.ReviewerAgent;
import com.niconicode.agent.chat.service.TraceLogger;
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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackerService {

    private static final Pattern SAFE_NODE_KEY = Pattern.compile("[^a-zA-Z0-9_\\-:.]+" );

    private static String safeKey(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";

    // 仅 ASCII 时走可读 key（与旧逻辑兼容）
    boolean asciiOnly = trimmed.chars().allMatch(ch -> ch <= 0x7F);
    if (asciiOnly) {
        String normalized = SAFE_NODE_KEY.matcher(trimmed).replaceAll("_");
        return normalized.isEmpty() ? "_" : normalized;
    }

    // 非 ASCII（例如中文分类名）：URL-safe Base64，保证“唯一且稳定”
    String b64 = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(trimmed.getBytes(StandardCharsets.UTF_8));
    return "b64_" + b64;
    }

    private static String categoryNodeId(String category) {
        return "category:" + safeKey(category);
    }

    private static String techNodeId(Long techId) {
        return "tech:" + techId;
    }

    private static String reportNodeId(Long reportId) {
        return "report:" + reportId;
    }

    private static void putIfNotNull(Map<String, Object> props, String key, Object value) {
        if (value != null) props.put(key, value);
    }

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
    private final TraceLogger traceLogger;
    private final GitHubSearchService gitHubSearchService;

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
    public Page<TechReport> listReportsByScore(int page, int size, String status, Long categoryId) {
        // 第一步：获取一个足够大的结果集，按发布时间降序（减少排序工作量）
        // 获取 page*size + buffer 的数据，以支持重排后获取正确的分页结果
        int fetchSize = Math.max(size * page + 100, 500); // 至少获取 500 条或 page*size+100

    String targetStatus = (status == null || status.isBlank()) ? "PUBLISHED" : status;

    LambdaQueryWrapper<TechReport> wrapper = new LambdaQueryWrapper<TechReport>()
        .eq(TechReport::getStatus, targetStatus)
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

    /**
     * 首页知识图谱：按“类型(category) -> 技术 -> 报道(techIndex Top N)”组织。
     *
     * @param reportsPerTech 每个技术最多展示的报道数量（按 techIndex desc，再按 publishedAt desc）
     * @param maxTechs       最多返回多少个技术（按 mentionCount desc），防止一次返回过大
     */
    public List<TrackerGraphResp.CategoryNode> buildGraph(int reportsPerTech, int maxTechs) {
        int cappedReportsPerTech = Math.max(1, Math.min(reportsPerTech, 10));
        int cappedMaxTechs = Math.max(1, Math.min(maxTechs, 200));

        List<TrackedTech> techs = techMapper.selectList(
                new LambdaQueryWrapper<TrackedTech>()
                        .eq(TrackedTech::getStatus, "ACTIVE")
                        .orderByDesc(TrackedTech::getMentionCount)
                        .last("LIMIT " + cappedMaxTechs)
        );

        // category -> node（保持插入顺序，便于前端稳定渲染）
        Map<String, TrackerGraphResp.CategoryNode> categoryMap = new LinkedHashMap<>();

        for (TrackedTech tech : techs) {
            String rawCategory = tech.getCategory();
            String category = (rawCategory == null || rawCategory.isBlank()) ? "未分类" : rawCategory.trim();
            if (category.isBlank()) category = "未分类";
            TrackerGraphResp.CategoryNode catNode = categoryMap.computeIfAbsent(category, k -> {
                TrackerGraphResp.CategoryNode n = new TrackerGraphResp.CategoryNode();
                n.setCategory(k);
                return n;
            });

            TrackerGraphResp.TechNode techNode = new TrackerGraphResp.TechNode();
            techNode.setTechId(tech.getId());
            techNode.setTechName(tech.getName());
            techNode.setTrackingMode(tech.getTrackingMode());
            techNode.setLastKnownVersion(tech.getLastKnownVersion());

            // 每个技术取 techIndex 最高的前 N 篇报道（同分按发布时间倒序）
            List<TechReport> reports = reportMapper.selectList(
                    new LambdaQueryWrapper<TechReport>()
                            .eq(TechReport::getStatus, "PUBLISHED")
                            .eq(TechReport::getTrackedTechId, tech.getId())
                            .orderByDesc(TechReport::getTechIndex)
                            .orderByDesc(TechReport::getPublishedAt)
                            .last("LIMIT " + cappedReportsPerTech)
            );
            techNode.setReports(reports);

            catNode.getTechs().add(techNode);
        }

        return new ArrayList<>(categoryMap.values());
    }

    /**
     * 语义网络（实体-关系）版知识图谱：nodes + edges。
     *
     * A 阶段：Category -> Tech -> Report
     * B 阶段（轻量增强）：Tech <-> Tech 共现边（当 Report 标题/内容同时提到多个 Tech 名称时）
     */
    public TrackerNetworkResp buildNetwork(int reportsPerTech, int maxTechs, String status, boolean includeTechCooccurrence) {
        int cappedReportsPerTech = Math.max(1, Math.min(reportsPerTech, 10));
        int cappedMaxTechs = Math.max(1, Math.min(maxTechs, 200));
        String targetStatus = (status == null || status.isBlank()) ? "PUBLISHED" : status;

        List<TrackedTech> techs = techMapper.selectList(
                new LambdaQueryWrapper<TrackedTech>()
                        .eq(TrackedTech::getStatus, "ACTIVE")
                        .orderByDesc(TrackedTech::getMentionCount)
                        .last("LIMIT " + cappedMaxTechs)
        );

        TrackerNetworkResp resp = new TrackerNetworkResp();
        Map<String, TrackerNetworkResp.Node> nodeMap = new LinkedHashMap<>();
        Set<String> edgeIds = new HashSet<>();

        // quick lookup：techName -> techId（用于共现识别）
        Map<String, Long> techNameToId = new HashMap<>();
        for (TrackedTech t : techs) {
            if (t.getName() != null && !t.getName().isBlank()) techNameToId.put(t.getName(), t.getId());
        }

        for (TrackedTech tech : techs) {
            String rawCategory = tech.getCategory();
            String category = (rawCategory == null || rawCategory.isBlank()) ? "未分类" : rawCategory.trim();
            if (category.isBlank()) category = "未分类";

            // category node
            String catId = categoryNodeId(category);
            String finalCategory = category;
            nodeMap.computeIfAbsent(catId, id -> {
                TrackerNetworkResp.Node n = new TrackerNetworkResp.Node();
                n.setId(id);
                n.setType("CATEGORY");
                n.setLabel(finalCategory);
                return n;
            });

            // tech node
            String tId = techNodeId(tech.getId());
            String finalCategory1 = category;
            nodeMap.computeIfAbsent(tId, id -> {
                TrackerNetworkResp.Node n = new TrackerNetworkResp.Node();
                n.setId(id);
                n.setType("TECH");
                n.setLabel(tech.getName());
                putIfNotNull(n.getProps(), "techId", tech.getId());
                putIfNotNull(n.getProps(), "category", finalCategory1);
                // debug: 如果你看到前端全部聚到“平台”，可以临时对比 rawCategory 是否带了不可见字符
                putIfNotNull(n.getProps(), "categoryRaw", rawCategory);
                putIfNotNull(n.getProps(), "trackingMode", tech.getTrackingMode());
                putIfNotNull(n.getProps(), "lastKnownVersion", tech.getLastKnownVersion());
                putIfNotNull(n.getProps(), "mentionCount", tech.getMentionCount());
                return n;
            });

            // edge: category -> tech
            String e1 = "e:HAS_TECH:" + catId + "->" + tId;
            if (edgeIds.add(e1)) {
                TrackerNetworkResp.Edge edge = new TrackerNetworkResp.Edge();
                edge.setId(e1);
                edge.setSource(catId);
                edge.setTarget(tId);
                edge.setType("HAS_TECH");
                edge.setWeight(1.0);
                resp.getEdges().add(edge);
            }

            // reports for tech
            List<TechReport> reports = reportMapper.selectList(
                    new LambdaQueryWrapper<TechReport>()
                            .eq(TechReport::getStatus, targetStatus)
                            .eq(TechReport::getTrackedTechId, tech.getId())
                            .orderByDesc(TechReport::getTechIndex)
                            .orderByDesc(TechReport::getPublishedAt)
                            .last("LIMIT " + cappedReportsPerTech)
            );

            for (TechReport r : reports) {
                String rId = reportNodeId(r.getId());
                nodeMap.computeIfAbsent(rId, id -> {
                    TrackerNetworkResp.Node n = new TrackerNetworkResp.Node();
                    n.setId(id);
                    n.setType("REPORT");
                    n.setLabel(r.getTitle());
                    putIfNotNull(n.getProps(), "reportId", r.getId());
                    putIfNotNull(n.getProps(), "techId", r.getTrackedTechId());
                    putIfNotNull(n.getProps(), "techIndex", r.getTechIndex());
                    putIfNotNull(n.getProps(), "publishedAt", r.getPublishedAt());
                    putIfNotNull(n.getProps(), "newVersion", r.getNewVersion());
                    putIfNotNull(n.getProps(), "status", r.getStatus());
                    return n;
                });

                // edge: tech -> report
                String e2 = "e:HAS_REPORT:" + tId + "->" + rId;
                if (edgeIds.add(e2)) {
                    TrackerNetworkResp.Edge edge = new TrackerNetworkResp.Edge();
                    edge.setId(e2);
                    edge.setSource(tId);
                    edge.setTarget(rId);
                    edge.setType("HAS_REPORT");
                    edge.setWeight(r.getTechIndex() != null ? Math.max(1.0, r.getTechIndex() / 100.0) : 1.0);
                    resp.getEdges().add(edge);
                }

                // B：tech co-occurrence（轻量：扫标题/内容包含 techName）
                if (includeTechCooccurrence) {
                    String hay = ((r.getTitle() == null ? "" : r.getTitle()) + "\n" + (r.getContent() == null ? "" : r.getContent()));
                    List<Long> mentionedTechIds = new ArrayList<>();
                    for (Map.Entry<String, Long> entry : techNameToId.entrySet()) {
                        String name = entry.getKey();
                        if (name != null && name.length() >= 2 && hay.contains(name)) {
                            mentionedTechIds.add(entry.getValue());
                        }
                    }
                    for (int i = 0; i < mentionedTechIds.size(); i++) {
                        for (int j = i + 1; j < mentionedTechIds.size(); j++) {
                            String a = techNodeId(mentionedTechIds.get(i));
                            String b = techNodeId(mentionedTechIds.get(j));
                            if (a.equals(b)) continue;
                            String k = (a.compareTo(b) <= 0) ? (a + "<->" + b) : (b + "<->" + a);
                            String e3 = "e:CO_OCCUR:" + k;
                            if (edgeIds.add(e3)) {
                                TrackerNetworkResp.Edge edge = new TrackerNetworkResp.Edge();
                                edge.setId(e3);
                                edge.setSource(a);
                                edge.setTarget(b);
                                edge.setType("CO_OCCUR");
                                edge.setWeight(1.0);
                                edge.getProps().put("evidenceReportId", r.getId());
                                resp.getEdges().add(edge);
                            }
                        }
                    }
                }
            }
        }

        resp.getNodes().addAll(nodeMap.values());
        return resp;
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
        if (tech.getChangelogUrl() != null) existing.setChangelogUrl(tech.getChangelogUrl());
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

        // P0-I fix: 不再要求必须有 githubRepo，SearchAgent 已支持 RSS/官方URL/更新日志页 作为数据源
        boolean hasAnySource = (tech.getGithubRepo() != null && !tech.getGithubRepo().isBlank())
                || (tech.getRssUrl() != null && !tech.getRssUrl().isBlank())
                || (tech.getOfficialUrl() != null && !tech.getOfficialUrl().isBlank())
                || (tech.getChangelogUrl() != null && !tech.getChangelogUrl().isBlank());
        if (!hasAnySource) return;

        // 初始化追踪上下文
        TraceLogger.TraceContext traceCtx = traceLogger.startTrace(-1L, techId);

        try {
            traceLogger.trace(traceCtx, "TRACKER_START", "tech=" + tech.getName()
                    + ", repo=" + tech.getGithubRepo()
                    + ", rss=" + (tech.getRssUrl() != null ? "yes" : "no")
                    + ", official=" + (tech.getOfficialUrl() != null ? "yes" : "no"));

            // Stage 1: SearchAgent — 多渠道信息搜集
            long searchStart = System.currentTimeMillis();
            SearchAgent.SearchResult searchResult = searchAgent.search(tech, traceCtx);
            int searchDuration = (int)(System.currentTimeMillis() - searchStart);

            if (!searchResult.isHasUpdate()) {
                traceLogger.trace(traceCtx, "SEARCH_AGENT", "No update detected, duration=" + searchDuration + "ms");
                tech.setLastCheckedAt(LocalDateTime.now());
                techMapper.updateById(tech);
                traceLogger.endTrace(traceCtx);
                return;
            }

            traceLogger.trace(traceCtx, "SEARCH_AGENT", "Update detected: " + searchResult.getDetectedVersion()
                    + " (" + searchResult.getUpdateMode() + "), sources=" + searchResult.getSourceUrls().size()
                    + ", duration=" + searchDuration + "ms");

        // 幂等去重：同一个 tech + detectedVersion 不应反复生成草稿。
        // - 若已存在 PUBLISHED：直接短路
        // - 若已存在 DRAFT：复用草稿，直接进入 Reviewer（不再跑 Writer）
        TechReport draft = null;
        String detectedVersion = searchResult.getDetectedVersion();
        if (detectedVersion != null && !detectedVersion.isBlank()
            && ("RELEASE".equals(searchResult.getUpdateMode()) || "TAG".equals(searchResult.getUpdateMode())
                || "OFFICIAL_CHANGELOG".equals(searchResult.getUpdateMode())
                || "OFFICIAL_URL".equals(searchResult.getUpdateMode()))) {
        TechReport publishedSameVersion = reportMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TechReport>()
                .eq(TechReport::getTrackedTechId, tech.getId())
                .eq(TechReport::getNewVersion, detectedVersion)
                .eq(TechReport::getStatus, "PUBLISHED")
                .last("LIMIT 1")
        );
        if (publishedSameVersion != null) {
            traceLogger.trace(traceCtx, "TRACKER_IDEMPOTENT_SKIP",
                "Already published for version=" + detectedVersion + ", reportId=" + publishedSameVersion.getId());
            tech.setLastCheckedAt(LocalDateTime.now());
            techMapper.updateById(tech);
            traceLogger.endTrace(traceCtx);
            return;
        }

        draft = reportMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TechReport>()
                .eq(TechReport::getTrackedTechId, tech.getId())
                .eq(TechReport::getNewVersion, detectedVersion)
                .eq(TechReport::getStatus, "DRAFT")
                .orderByDesc(TechReport::getCreatedAt)
                .last("LIMIT 1")
        );
        if (draft != null) {
            traceLogger.trace(traceCtx, "TRACKER_IDEMPOTENT_REUSE",
                "Reuse existing draft for version=" + detectedVersion + ", draftReportId=" + draft.getId());
        }
        }

            // Stage 2: WriterAgent — 撰写高质量报道（P2-N: 返回 WriteResult，含 AI 生成标题）
        WriterAgent.WriteResult writeResult;
        if (draft == null) {
        long writerStart = System.currentTimeMillis();
        writeResult = writerAgent.write(tech, searchResult, traceCtx);
        int writerDuration = (int)(System.currentTimeMillis() - writerStart);
        traceLogger.trace(traceCtx, "WRITER_AGENT", "Report generated, length=" + writeResult.getContent().length()
            + ", title=" + writeResult.getTitle()
            + ", duration=" + writerDuration + "ms");
        } else {
        // 复用草稿时，不再重复生成内容
        writeResult = new WriterAgent.WriteResult(draft.getTitle(), draft.getContent());
        traceLogger.trace(traceCtx, "WRITER_AGENT", "Skipped (reuse draft), draftReportId=" + draft.getId());
        }

        if (draft == null) {
        // P0: 草稿先落库（避免 Reviewer/LLM 超时导致“生成了但页面看不到”的体验）
        draft = new TechReport();
        draft.setTrackedTechId(tech.getId());
        draft.setTitle(writeResult.getTitle());
        draft.setContent(writeResult.getContent());
        draft.setNewVersion(searchResult.getDetectedVersion());
        draft.setChangeSummary(
            searchResult.getRawInfoSummary() != null
                ? searchResult.getRawInfoSummary().substring(0,
                Math.min(500, searchResult.getRawInfoSummary().length()))
                : "");
        draft.setSourceUrls(buildSourceUrlsJson(searchResult));
        draft.setStatus("DRAFT");
        reportMapper.insert(draft);
        traceLogger.trace(traceCtx, "DRAFT_SAVED", "draftReportId=" + draft.getId());
        }

            // Stage 3: ReviewerAgent — 审核 + 评分 + 发布决策
            long reviewStart = System.currentTimeMillis();
            ReviewerAgent.ReviewResult review = reviewerAgent.review(tech, writeResult.getContent(), searchResult, traceCtx);
            int reviewDuration = (int)(System.currentTimeMillis() - reviewStart);

            if (review.isApproved()) {
        // 发布：更新同一条草稿记录，而不是新插入
        draft.setContent(review.getRevisedContent());
        draft.setStatus("PUBLISHED");
        draft.setTechIndex(review.getTechIndex());
        draft.setPublishedAt(LocalDateTime.now());
        reportMapper.updateById(draft);

        // 仅在发布后同步到知识库，避免把未审核内容写入知识库
        syncToKnowledge(draft, tech, traceCtx);

                traceLogger.trace(traceCtx, "REVIEWER_AGENT", "Report approved: techIndex=" + review.getTechIndex()
                        + ", published=true, duration=" + reviewDuration + "ms");
        traceLogger.trace(traceCtx, "TRACKER_SUCCESS", "Report published: " + draft.getId());
            } else {
                traceLogger.trace(traceCtx, "REVIEWER_AGENT", "Report rejected: " + review.getRejectionReason()
                        + ", duration=" + reviewDuration + "ms");
            }

            // 更新追踪状态
            updateTrackingState(tech, searchResult, traceCtx);

        } catch (Exception e) {
            log.error("Multi-agent pipeline failed for {}", tech.getName(), e);
            traceLogger.traceError(traceCtx, "TRACKER_PIPELINE", e);
        } finally {
            tech.setLastCheckedAt(LocalDateTime.now());
            techMapper.updateById(tech);
            traceLogger.endTrace(traceCtx);
        }
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

    private void syncToKnowledge(TechReport report, TrackedTech tech,
                                TraceLogger.TraceContext traceCtx) {
        try {
            KnowledgeDocReq docReq = new KnowledgeDocReq();
            docReq.setTitle(report.getTitle());
            docReq.setContent(report.getContent());
            docReq.setSourceType("TRACKER_REPORT");
            docReq.setSourceId(report.getId());
            docReq.setTags(tech.getName() + "," + tech.getCategory());
            // P2-O: 使用幂等方法，防止重复同步时产生重复文档
            knowledgeService.createOrUpdateDoc(docReq);
            traceLogger.trace(traceCtx, "KNOWLEDGE_SYNC", "synced reportId=" + report.getId());
        } catch (Exception e) {
            log.warn("Failed to sync report to knowledge base: {}", e.getMessage());
            traceLogger.trace(traceCtx, "KNOWLEDGE_SYNC", "failed: " + e.getMessage());
        }
    }

    private void updateTrackingState(TrackedTech tech, SearchAgent.SearchResult searchResult,
                                     TraceLogger.TraceContext traceCtx) {
        if ("RELEASE".equals(searchResult.getUpdateMode())
                || "TAG".equals(searchResult.getUpdateMode())
                || "OFFICIAL_CHANGELOG".equals(searchResult.getUpdateMode())
                || "OFFICIAL_URL".equals(searchResult.getUpdateMode())) {
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
        traceLogger.trace(traceCtx, "TRACKING_STATE_UPDATE",
                "version=" + tech.getLastKnownVersion() + ", mode=" + tech.getTrackingMode());
    }

    /**
     * 根据技术名称、分类和报道内容生成技术指数 (0-1000)
     * @deprecated 已被 ReviewerAgent.scoreTechIndex() 替代，保留用于手动创建报道
     */
    private int generateTechIndex(String techName, String category, String content) {
    // 注意：不要使用 String.format / """.formatted
    // 当 content 内部包含 '%'（例如 Markdown 里常见的 100%）时，Formatter 会抛 UnknownFormatConversionException。
    String contentSummary = content.length() > 500 ? content.substring(0, 500) : content;
    String prompt = "根据以下技术名称、分类和报道内容，生成一个 0-1000 的技术指数，用于评估这次更新的质量和影响因子。\n\n"
        + "评分标准：\n"
        + "- 0-100：无人在意的小型开源项目的一次 README 或小补丁更新\n"
        + "- 100-300：小型或不知名项目的常规更新\n"
        + "- 300-500：知名开源框架或工具的常规更新\n"
        + "- 500-700：重要技术框架（如 Spring、React、Vue 等）的中等更新\n"
        + "- 700-900：影响广泛的重要技术（如 Node.js、Docker 等）的重要更新\n"
        + "- 900-1000：最前沿的大模型（如 Claude、GPT 等）或操作系统级别的重要版本迭代\n\n"
        + "技术名称: " + techName + "\n"
        + "分类: " + category + "\n"
        + "报道内容摘要（前500字）:\n"
        + contentSummary + "\n\n"
        + "请直接返回一个数字 (0-1000)，不要其他说明文字。\n";

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
    String title = report.getTitle();
    String contentSummary = report.getContent().substring(0, Math.min(500, report.getContent().length()));
    // 注意：report content 可能包含 '%'，不要用 formatted
    String tagsPrompt = """
        根据以下技术报道的标题和内容，生成 3-5 个逗号分隔的标签（中文或英文），要求：
        1. 标签应该体现核心技术和更新内容
        2. 避免过于通用的标签
        3. 直接返回标签列表，不要其他说明文字

        标题: """ + title + "\r\n"
        + "内容摘要: \r\n" + contentSummary + "\r\n";

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

        // 自动补全：尝试根据技术名在 GitHub 上检索最相关且 star 最高的仓库
        // 注意：这是“尽力而为”的自动填充，后续可在管理后台人工校正。
        try {
            GitHubSearchService.RepoCandidate best = gitHubSearchService.findBestRepoByName(topic.getKeyword());
            if (best != null && best.getFullName() != null && !best.getFullName().isBlank()) {
                newTech.setGithubRepo(best.getFullName());
                // SearchAgent 已升级为聚合检查，因此默认模式用 RELEASE 即可
                newTech.setTrackingMode("RELEASE");

                TraceLogger.TraceContext traceCtx = null;
                try {
                    traceCtx = traceLogger.startTrace(null, null);
                    traceLogger.trace(traceCtx, "PROMOTE_GITHUB_AUTOFILL",
                            "keyword=" + topic.getKeyword() + " repo=" + best.getFullName()
                                    + " stars=" + best.getStars());
                } finally {
                    if (traceCtx != null) {
                        traceLogger.endTrace(traceCtx);
                    }
                }

                log.info("Auto-filled github repo for promoted tech '{}': {} (stars={})",
                        topic.getKeyword(), best.getFullName(), best.getStars());
            }
        } catch (Exception e) {
            log.debug("Failed to auto-fill github repo for promoted tech {}: {}", topic.getKeyword(), e.getMessage());
        }
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
     * 按日期范围查询已发布报道
     */
    public List<TechReport> getReportsByDateRange(LocalDateTime start, LocalDateTime end, int limit) {
        return reportMapper.selectList(
                new LambdaQueryWrapper<TechReport>()
                        .eq(TechReport::getStatus, "PUBLISHED")
                        .ge(TechReport::getPublishedAt, start)
                        .le(TechReport::getPublishedAt, end)
                        .orderByDesc(TechReport::getPublishedAt)
                        .last("LIMIT " + limit)
        );
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
