package com.niconicode.agent.tracker.agent;

import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.GitHubMonitorService;
import com.niconicode.agent.tracker.service.OfficialSiteCrawler;
import com.niconicode.agent.tracker.service.RssService;
import com.niconicode.agent.chat.service.TraceLogger;
import com.rometools.rome.feed.synd.SyndEntry;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信息搜集 Agent
 * 从多个数据源全面搜集技术更新信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAgent {

    private final GitHubMonitorService githubMonitor;
    private final RssService rssService;
    private final OfficialSiteCrawler officialCrawler;
    private final ChatLanguageModel chatModel;
    private final TraceLogger traceLogger;

    /** 用于从官方页面标题/内容中提取版本号的正则，覆盖常见格式如 v1.2.3 / 1.2.3 / Release 4.0 */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(?:v|version\\s*|release\\s*)(\\d+\\.\\d+(?:\\.\\d+)*(?:[-.][a-zA-Z0-9]+)?)",
                    Pattern.CASE_INSENSITIVE);

    /** 访问官方页面时使用的 HTTP 超时时间 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(8);

    /** 静态 HttpClient：复用连接，不跟随重定向（防止无限跳转） */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public enum DataSufficiency { HIGH, MEDIUM, LOW }

    @Data
    public static class SearchResult {
        private boolean hasUpdate;
        private String detectedVersion;
        private String updateMode;        // RELEASE, TAG, COMMIT, RSS, OFFICIAL_URL
        private GitHubReleaseInfo releaseInfo;
        private String tagInfo;
        private List<GitHubCommitInfo> recentCommits = new ArrayList<>();
    /** 近30日内合并的 PR 数（活跃度补充指标） */
    private Integer mergedPrs30Days;
    /** 近30日内关闭的 Issue 数（生态活跃度补充指标） */
    private Integer closedIssues30Days;
        private String rawInfoSummary;
        private DataSufficiency dataSufficiency = DataSufficiency.LOW;
        private List<String> sourceUrls = new ArrayList<>();
        /** P0-I: RSS 源搜集到的原始内容 */
        private String rssContent;
        /** P0-I: 官方 URL 页面搜集到的原始内容 */
        private String officialUrlContent;
        /** 官网更新日志页提取的条目列表 */
        private List<OfficialSiteCrawler.ChangelogEntry> changelogEntries = new ArrayList<>();
        /** 官网更新日志页原始内容摘要 */
        private String officialChangelogContent;
    }

    public SearchResult search(TrackedTech tech, TraceLogger.TraceContext traceCtx) {
        SearchResult result = new SearchResult();

        boolean hasGithub = tech.getGithubRepo() != null && !tech.getGithubRepo().isBlank();
        boolean hasRss = tech.getRssUrl() != null && !tech.getRssUrl().isBlank();
        boolean hasOfficialUrl = tech.getOfficialUrl() != null && !tech.getOfficialUrl().isBlank();
        boolean hasChangelog = tech.getChangelogUrl() != null && !tech.getChangelogUrl().isBlank();

        if (!hasGithub && !hasRss && !hasOfficialUrl && !hasChangelog) {
            traceLogger.trace(traceCtx, "SEARCH_SKIP", "no data sources configured");
            result.setHasUpdate(false);
            return result;
        }

        String mode = tech.getTrackingMode() != null ? tech.getTrackingMode() : "RELEASE";
        traceLogger.trace(traceCtx, "SEARCH_MODE", "mode=" + mode
                + " github=" + hasGithub + " rss=" + hasRss
                + " official=" + hasOfficialUrl + " changelog=" + hasChangelog);

        if (hasGithub) {
            // 追踪模式升级：无论模式如何，都尽量把 GitHub 的多个维度一起采集。
            // - RELEASE/TAG：以版本更新为主，但补充 commit 与 PR/issue 活跃度
            // - COMMIT：以开发动态为主，但也补充 release/tag 作为"里程碑"
            searchGitHubAggregate(tech, result, traceCtx);

            // 兼容：如果用户显式指定 mode=TAG，且 release 未命中，可把 TAG 信息当作 detectedVersion
            if (!result.isHasUpdate() && "TAG".equals(mode)) {
                // 仅在没有任何更新时才做一次 tag-only 的快速检查
                searchTagMode(tech, result, traceCtx);
            }
        }

        // GitHub 未找到更新（或无 GitHub），优先尝试官网更新日志页
        // 优先级：changelogUrl(显式) > officialUrl 试爬(用户常错填) > rssUrl > officialUrl 兜底版本扫描
        if (!result.isHasUpdate() && hasChangelog) {
            // changelogUrl 是显式标注，允许用 title 兜底当作版本
            searchOfficialChangelog(tech, tech.getChangelogUrl(), false, result, traceCtx);
        }

        // P0-J: 用户经常把更新日志 URL 填到 officialUrl 字段（而不是 changelogUrl），
        // 此时也尝试用爬虫解析。但用严格模式：要求最新条目能提取出真实版本号，
        // 否则认为这是首页/导航页而不是 changelog，避免假阳性。
        if (!result.isHasUpdate() && hasOfficialUrl
                && !tech.getOfficialUrl().equalsIgnoreCase(tech.getChangelogUrl())) {
            searchOfficialChangelog(tech, tech.getOfficialUrl(), true, result, traceCtx);
        }

        // 更新日志页也未找到，尝试 RSS 源
        if (!result.isHasUpdate() && hasRss) {
            searchRssSource(tech, result, traceCtx);
        }

        // RSS 也未找到（或无 RSS），尝试官方首页（仅做版本号扫描，作为最后兜底）
        if (!result.isHasUpdate() && hasOfficialUrl) {
            searchOfficialUrl(tech, result, traceCtx);
        }

        if (result.isHasUpdate()) {
            // AI 总结搜集到的原始信息
            long summarizeStart = System.currentTimeMillis();
            result.setRawInfoSummary(summarizeRawInfo(result, tech));
            int summarizeDuration = (int)(System.currentTimeMillis() - summarizeStart);
            traceLogger.trace(traceCtx, "SEARCH_SUMMARIZE", "duration=" + summarizeDuration + "ms");

            result.setDataSufficiency(evaluateDataSufficiency(result));
            traceLogger.trace(traceCtx, "SEARCH_DATA_SUFFICIENCY", "level=" + result.getDataSufficiency());
        }

        return result;
    }

    /**
     * GitHub 聚合检查：一次性抓取 release/tag/commit + issue/pr 活跃度。
     * 更新判定规则：
     * 1) 若找到 new release/tag/commit 任一，则 hasUpdate=true。
     * 2) 若版本无变化，但 PR/issue 活跃度很高（merged PR 或 closed issue 非零），
     *    不直接认为是"需要发布报道"的更新（避免噪声），只作为 rawInfoSummary 的补充。
     */
    private void searchGitHubAggregate(TrackedTech tech, SearchResult result, TraceLogger.TraceContext traceCtx) {
        String mode = tech.getTrackingMode() != null ? tech.getTrackingMode() : "RELEASE";
        traceLogger.trace(traceCtx, "SEARCH_GITHUB_AGG_START", "mode=" + mode + " repo=" + tech.getGithubRepo());

        // 1) 版本维度：release / tag
        String newVersion = githubMonitor.checkLatestRelease(
                tech.getGithubRepo(), tech.getLastKnownVersion());
        if (newVersion != null) {
            traceLogger.trace(traceCtx, "SEARCH_GITHUB_RELEASE", "newVersion=" + newVersion);
            result.setHasUpdate(true);
            result.setDetectedVersion(newVersion);
            result.setUpdateMode("RELEASE");

            GitHubReleaseInfo releaseInfo = githubMonitor.getFullReleaseInfo(
                    tech.getGithubRepo(), newVersion);
            result.setReleaseInfo(releaseInfo);
            if (releaseInfo.getHtmlUrl() != null && !releaseInfo.getHtmlUrl().isBlank()) {
                result.getSourceUrls().add(releaseInfo.getHtmlUrl());
            }
        } else {
            // release 不命中时，仅记录，无需打 warning（有些仓库压根没有 release）
            traceLogger.trace(traceCtx, "SEARCH_GITHUB_RELEASE", "noNewRelease");
        }

        // 2) tag 维度：当 mode=TAG 或 release 没命中时，都补充一次 tag 检查
        // 目的：在无 release 的仓库里，tag 常常是"等价 release"。
        if (!result.isHasUpdate() || "TAG".equals(mode)) {
            String newTag = githubMonitor.checkLatestTag(
                    tech.getGithubRepo(), tech.getLastKnownVersion());
            if (newTag != null) {
                traceLogger.trace(traceCtx, "SEARCH_GITHUB_TAG", "tag=" + newTag);
                if (!result.isHasUpdate()) {
                    result.setHasUpdate(true);
                    result.setDetectedVersion(newTag);
                    result.setUpdateMode("TAG");
                    result.setTagInfo(newTag);
                    result.getSourceUrls().add("https://github.com/" + tech.getGithubRepo() + "/tags");
                } else {
                    // 已有 release 更新时，tag 作为补充信息
                    result.setTagInfo(newTag);
                }
            } else {
                traceLogger.trace(traceCtx, "SEARCH_GITHUB_TAG", "noNewTag");
            }
        }

        // 3) commit 维度：始终补充近期 commits（用于粒度更细的变更摘要）
        try {
            String sinceDate = LocalDateTime.now().minusDays(7)
                    .atZone(ZoneId.systemDefault()).toInstant().toString();
            List<GitHubCommitInfo> commits = githubMonitor.getCommitsSince(
                    tech.getGithubRepo(), sinceDate, "COMMIT".equals(mode) ? 20 : 10);
            if (!commits.isEmpty()) {
                result.setRecentCommits(commits);
                traceLogger.trace(traceCtx, "SEARCH_GITHUB_COMMITS", "count=" + commits.size());

                // 若模式是 COMMIT，则用"是否有新 commit sha"作为更新判定
                if ("COMMIT".equals(mode) && tech.getLastKnownCommitSha() != null) {
                    String latestSha = commits.get(0).getSha();
                    if (latestSha != null && !latestSha.isBlank()
                            && !latestSha.equalsIgnoreCase(tech.getLastKnownCommitSha())) {
                        result.setHasUpdate(true);
                        result.setUpdateMode("COMMIT");
                        String shortSha = latestSha.length() > 7 ? latestSha.substring(0, 7) : latestSha;
                        result.setDetectedVersion(shortSha);
                        result.getSourceUrls().add("https://github.com/" + tech.getGithubRepo() + "/commits");
                        traceLogger.trace(traceCtx, "SEARCH_GITHUB_COMMIT_NEW", "sha=" + shortSha);
                    }
                }
            }
        } catch (Exception e) {
            traceLogger.trace(traceCtx, "SEARCH_GITHUB_COMMITS", "error=" + e.getMessage());
            log.debug("Failed to get commits for {}", tech.getName());
        }

        // 4) PR/Issue 活跃度维度：用 search 接口统计（无需额外权限），仅作为补充信息
        try {
            GitHubMonitorService.IssueAndPRStats stats = githubMonitor.getIssueAndPRStats30Days(tech.getGithubRepo());
            result.setClosedIssues30Days(stats.getClosedIssues());
            result.setMergedPrs30Days(stats.getMergedPRs());
            traceLogger.trace(traceCtx, "SEARCH_GITHUB_ISSUE_PR_30D",
                    "closedIssues=" + stats.getClosedIssues() + " mergedPRs=" + stats.getMergedPRs());
        } catch (Exception e) {
            traceLogger.trace(traceCtx, "SEARCH_GITHUB_ISSUE_PR_30D", "error=" + e.getMessage());
        }

        traceLogger.trace(traceCtx, "SEARCH_GITHUB_AGG_DONE", "hasUpdate=" + result.isHasUpdate());
    }

    private void searchReleaseMode(TrackedTech tech, SearchResult result, TraceLogger.TraceContext traceCtx) {
        // 1. 检查 GitHub Release
        String newVersion = githubMonitor.checkLatestRelease(
                tech.getGithubRepo(), tech.getLastKnownVersion());

        if (newVersion != null) {
            traceLogger.trace(traceCtx, "SEARCH_RELEASE_CHECK", "newVersion=" + newVersion);
            result.setHasUpdate(true);
            result.setDetectedVersion(newVersion);
            result.setUpdateMode("RELEASE");

            GitHubReleaseInfo releaseInfo = githubMonitor.getFullReleaseInfo(
                    tech.getGithubRepo(), newVersion);
            result.setReleaseInfo(releaseInfo);

            if (releaseInfo.getHtmlUrl() != null && !releaseInfo.getHtmlUrl().isBlank()) {
                result.getSourceUrls().add(releaseInfo.getHtmlUrl());
            }

            // 补充: 获取近期 commits 作为补充信息
            try {
                String sinceDate = LocalDateTime.now().minusDays(7)
                        .atZone(ZoneId.systemDefault()).toInstant().toString();
                List<GitHubCommitInfo> commits = githubMonitor.getCommitsSince(
                        tech.getGithubRepo(), sinceDate, 10);
                result.setRecentCommits(commits);
                traceLogger.trace(traceCtx, "SEARCH_SUPPLEMENTARY_COMMITS", "count=" + commits.size());
            } catch (Exception e) {
                log.debug("Failed to get supplementary commits for {}", tech.getName());
            }
            return;
        }

        traceLogger.trace(traceCtx, "SEARCH_RELEASE_CHECK", "noRelease, trying tag fallback");

        // 2. Release 404 降级: 尝试 Tag
        String newTag = githubMonitor.checkLatestTag(
                tech.getGithubRepo(), tech.getLastKnownVersion());
        if (newTag != null) {
            traceLogger.trace(traceCtx, "SEARCH_TAG_FALLBACK", "tag=" + newTag);
            result.setHasUpdate(true);
            result.setDetectedVersion(newTag);
            result.setUpdateMode("TAG");
            result.setTagInfo(newTag);
            result.getSourceUrls().add("https://github.com/" + tech.getGithubRepo() + "/tags");
        }
        // 注意: RSS 和 officialUrl 的降级由 search() 统一处理，不在此处重复调用
    }

    private void searchTagMode(TrackedTech tech, SearchResult result, TraceLogger.TraceContext traceCtx) {
        String newTag = githubMonitor.checkLatestTag(
                tech.getGithubRepo(), tech.getLastKnownVersion());
        if (newTag != null) {
            traceLogger.trace(traceCtx, "SEARCH_TAG_CHECK", "tag=" + newTag);
            result.setHasUpdate(true);
            result.setDetectedVersion(newTag);
            result.setUpdateMode("TAG");
            result.setTagInfo(newTag);
            result.getSourceUrls().add("https://github.com/" + tech.getGithubRepo() + "/tags");
        } else {
            traceLogger.trace(traceCtx, "SEARCH_TAG_CHECK", "noNewTag");
        }
    }

    private void searchCommitMode(TrackedTech tech, SearchResult result, TraceLogger.TraceContext traceCtx) {
        GitHubCommitInfo latestCommit = githubMonitor.checkLatestCommit(
                tech.getGithubRepo(), tech.getLastKnownCommitSha());
        if (latestCommit != null) {
            result.setHasUpdate(true);
            String sha = latestCommit.getSha();
            result.setDetectedVersion(sha.length() > 7 ? sha.substring(0, 7) : sha);
            result.setUpdateMode("COMMIT");

            String sinceDate = LocalDateTime.now().minusHours(24)
                    .atZone(ZoneId.systemDefault()).toInstant().toString();
            List<GitHubCommitInfo> commits = githubMonitor.getCommitsSince(
                    tech.getGithubRepo(), sinceDate, 20);
            if (commits.isEmpty()) {
                commits = List.of(latestCommit);
            }
            result.setRecentCommits(commits);
            result.getSourceUrls().add("https://github.com/" + tech.getGithubRepo() + "/commits");
            traceLogger.trace(traceCtx, "SEARCH_COMMIT_CHECK", "commits=" + commits.size());
        } else {
            traceLogger.trace(traceCtx, "SEARCH_COMMIT_CHECK", "noNewCommit");
        }
    }

    /**
     * 从官网更新日志页搜集更新信息。
     * 使用 OfficialSiteCrawler 抓取并解析结构化更新条目，
     * 与 lastKnownVersion 对比判断是否为新版本。
     *
     * <p>适用于大模型官网（如 DeepSeek API 更新页）等无 GitHub Release 的场景。</p>
     *
     * @param url       要爬取的 URL（changelogUrl 或被当作 changelog 尝试的 officialUrl）
     * @param strict    严格模式：true 时要求最新条目必须能提取出真实版本号（不允许用 title 兜底）。
     *                  当 url 来自 officialUrl 时建议设为 true，避免把首页/导航页当成更新日志。
     */
    private void searchOfficialChangelog(TrackedTech tech, String url, boolean strict,
                                         SearchResult result, TraceLogger.TraceContext traceCtx) {
        if (url == null || url.isBlank()) return;

        traceLogger.trace(traceCtx, "SEARCH_CHANGELOG_START", "url=" + url + " strict=" + strict);

        try {
            List<OfficialSiteCrawler.ChangelogEntry> entries = officialCrawler.crawlChangelog(url);

            if (entries.isEmpty()) {
                traceLogger.trace(traceCtx, "SEARCH_CHANGELOG_RESULT", "no entries found");
                return;
            }

            // 取最新条目判断是否为新版本
            OfficialSiteCrawler.ChangelogEntry latest = entries.get(0);
            String detectedVersion;
            if (strict) {
                // 严格模式：只接受"标题中含版本号"的条目，避免把首页随机内容当作 changelog
                detectedVersion = officialCrawler.extractVersion(latest.getTitle());
                if (detectedVersion == null || detectedVersion.isBlank()) {
                    traceLogger.trace(traceCtx, "SEARCH_CHANGELOG_RESULT",
                            "strict mode: no version in latest entry title='"
                                    + latest.getTitle() + "', abort (url=" + url + ")");
                    return;
                }
            } else {
                // 非严格：允许从 title+content 一起提取，提取不到时用 title 兜底
                detectedVersion = latest.getVersion();
                if (detectedVersion == null || detectedVersion.isBlank()) {
                    detectedVersion = latest.getTitle();
                }
            }

            // 判断是否是新版本
            boolean isNew = true;
            if (detectedVersion != null && tech.getLastKnownVersion() != null) {
                isNew = !detectedVersion.equalsIgnoreCase(tech.getLastKnownVersion())
                        && !tech.getLastKnownVersion().contains(detectedVersion)
                        && !detectedVersion.contains(tech.getLastKnownVersion());
            }

            if (!isNew) {
                traceLogger.trace(traceCtx, "SEARCH_CHANGELOG_RESULT",
                        "version=" + detectedVersion + " already known");
                return;
            }

            result.setHasUpdate(true);
            result.setUpdateMode("OFFICIAL_CHANGELOG");
            result.setDetectedVersion(detectedVersion);
            result.setChangelogEntries(entries);

            // 构建原始内容摘要
            StringBuilder contentBuilder = new StringBuilder();
            contentBuilder.append("[官网更新日志] ").append(latest.getTitle()).append("\n");
            if (latest.getDate() != null) {
                contentBuilder.append("发布日期: ").append(latest.getDate()).append("\n");
            }
            if (latest.getLink() != null) {
                contentBuilder.append("链接: ").append(latest.getLink()).append("\n");
                result.getSourceUrls().add(latest.getLink());
            }
            contentBuilder.append("\n内容摘要:\n").append(latest.getContent()).append("\n");

            // 附加其他条目作为上下文
            if (entries.size() > 1) {
                contentBuilder.append("\n历史版本:\n");
                for (int i = 1; i < Math.min(entries.size(), 5); i++) {
                    OfficialSiteCrawler.ChangelogEntry e = entries.get(i);
                    contentBuilder.append("- ").append(e.getTitle());
                    if (e.getDate() != null) {
                        contentBuilder.append(" (").append(e.getDate()).append(")");
                    }
                    contentBuilder.append("\n");
                }
            }

            result.setOfficialChangelogContent(contentBuilder.toString());

            // 始终将更新日志页本身也加入来源
            result.getSourceUrls().add(url);

            traceLogger.trace(traceCtx, "SEARCH_CHANGELOG_RESULT",
                    "found version=" + detectedVersion + " entries=" + entries.size()
                            + " latestTitle=" + latest.getTitle());

        } catch (Exception e) {
            traceLogger.trace(traceCtx, "SEARCH_CHANGELOG_RESULT", "error: " + e.getMessage());
            log.debug("Failed to crawl changelog for {}: {}", tech.getName(), e.getMessage());
        }
    }

    /**
     * P0-I: 从 RSS 源搜集更新信息。
     * 解析最新几条 Entry 的标题/描述，尝试提取版本号。
     * 若无法确认版本，则将最新 Entry 内容作为更新摘要并标记 hasUpdate=true。
     */
    private void searchRssSource(TrackedTech tech, SearchResult result, TraceLogger.TraceContext traceCtx) {
        String rssUrl = tech.getRssUrl();
        if (rssUrl == null || rssUrl.isBlank()) return;

        traceLogger.trace(traceCtx, "SEARCH_RSS_START", "url=" + rssUrl);
        List<SyndEntry> entries = rssService.fetchLatestEntries(rssUrl, 5);

        if (entries.isEmpty()) {
            traceLogger.trace(traceCtx, "SEARCH_RSS_RESULT", "empty feed");
            return;
        }

        // 尝试从最新条目的标题中提取版本号
        String detectedVersion = null;
        SyndEntry latestEntry = entries.get(0);

        for (SyndEntry entry : entries) {
            String title = entry.getTitle() != null ? entry.getTitle() : "";
            String desc = entry.getDescription() != null ? entry.getDescription().getValue() : "";
            Matcher m = VERSION_PATTERN.matcher(title + " " + desc);
            if (m.find()) {
                detectedVersion = m.group(1);
                latestEntry = entry;
                break;
            }
        }

        // 判断是否是"新"版本（与 lastKnownVersion 对比）
        boolean isNew = true;
        if (detectedVersion != null && tech.getLastKnownVersion() != null) {
            isNew = !detectedVersion.equalsIgnoreCase(tech.getLastKnownVersion())
                    && !tech.getLastKnownVersion().contains(detectedVersion);
        }

        if (!isNew) {
            traceLogger.trace(traceCtx, "SEARCH_RSS_RESULT", "version=" + detectedVersion + " already known");
            return;
        }

        result.setHasUpdate(true);
        result.setUpdateMode("RSS");
        if (detectedVersion != null) {
            result.setDetectedVersion(detectedVersion);
        }

        // 拼接 RSS 内容作为原始摘要的补充
        StringBuilder rssSummary = new StringBuilder();
        rssSummary.append("[RSS Feed] ").append(latestEntry.getTitle()).append("\n");
        if (latestEntry.getLink() != null) {
            rssSummary.append("链接: ").append(latestEntry.getLink()).append("\n");
            result.getSourceUrls().add(latestEntry.getLink());
        }
        if (latestEntry.getDescription() != null) {
            String desc = latestEntry.getDescription().getValue();
            // 截断过长的 HTML 描述
            if (desc != null && desc.length() > 1000) {
                desc = desc.substring(0, 1000) + "...";
            }
            rssSummary.append("摘要: ").append(desc).append("\n");
        }
        // 附加其余条目标题作为上下文
        for (int i = 1; i < entries.size(); i++) {
            SyndEntry e = entries.get(i);
            if (e.getTitle() != null) {
                rssSummary.append("- ").append(e.getTitle()).append("\n");
            }
        }

        result.setRssContent(rssSummary.toString());
        traceLogger.trace(traceCtx, "SEARCH_RSS_RESULT",
                "found version=" + detectedVersion + " entries=" + entries.size());
    }

    /**
     * P0-I: 从官方页面 URL 搜集更新信息（最后的兜底）。
     * 用 JSoup 解析 HTML，剥掉 head/meta/script/style/nav 等非内容节点，
     * 从可见正文中用版本正则匹配。
     *
     * <p>修复：以前只扫前 4KB 原始 HTML，会被 Docusaurus 等生成器的
     * <code>&lt;meta name="generator" content="Docusaurus v3.1.0"&gt;</code> 误识别为产品版本。
     * 现在剥掉 head/meta 后再匹配，从根本上避免这类假阳性。</p>
     */
    private void searchOfficialUrl(TrackedTech tech, SearchResult result, TraceLogger.TraceContext traceCtx) {
        String officialUrl = tech.getOfficialUrl();
        if (officialUrl == null || officialUrl.isBlank()) return;

        traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_START", "url=" + officialUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(officialUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT",
                        "http status=" + response.statusCode());
                return;
            }

            String body = response.body();
            if (body == null || body.isBlank()) {
                traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT", "empty body");
                return;
            }

            // 用 JSoup 解析并剥掉非内容节点
            Document doc = Jsoup.parse(body, officialUrl);
            // 抽取 <title>（在剥 head 之前）
            String pageTitle = doc.title().trim();
            // 剥掉所有"非可见内容"节点：head/script/style/meta/link/noscript/nav/header/footer/sidebar
            doc.select("head, script, style, noscript, link, meta, nav, header, footer, "
                    + ".navbar, .nav, .sidebar, .menu, .breadcrumbs, .toc, .footer").remove();
            String visibleText = doc.body().text();
            // 限制扫描范围，避免极长页面
            if (visibleText.length() > 16384) {
                visibleText = visibleText.substring(0, 16384);
            }

            // 在 title + 可见文本中搜索版本（pageTitle 来自 head 已剥离前的提取，仍可用）
            // 用 OfficialSiteCrawler.extractVersion: 优先匹配模型名(DeepSeek-V3 等)，再退到通用 v1.2.3 模式
            String haystack = pageTitle + " " + visibleText;
            String detectedVersion = officialCrawler.extractVersion(haystack);

            if (detectedVersion == null) {
                traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT", "no version pattern found");
                return;
            }
            if (tech.getLastKnownVersion() != null
                    && (detectedVersion.equalsIgnoreCase(tech.getLastKnownVersion())
                    || tech.getLastKnownVersion().contains(detectedVersion))) {
                traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT",
                        "version=" + detectedVersion + " already known");
                return;
            }

            result.setHasUpdate(true);
            result.setUpdateMode("OFFICIAL_URL");
            result.setDetectedVersion(detectedVersion);
            result.getSourceUrls().add(officialUrl);

            String officialContent = "[官方页面] " + pageTitle + "\n"
                    + "URL: " + officialUrl + "\n"
                    + "检测到版本: " + detectedVersion + "\n";
            result.setOfficialUrlContent(officialContent);

            traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT",
                    "version=" + detectedVersion + " title=" + pageTitle);

        } catch (java.net.http.HttpTimeoutException e) {
            traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT", "timeout: " + officialUrl);
            log.debug("Official URL timeout for {}: {}", tech.getName(), officialUrl);
        } catch (Exception e) {
            traceLogger.trace(traceCtx, "SEARCH_OFFICIAL_RESULT", "error: " + e.getMessage());
            log.debug("Failed to check official URL for {}: {}", tech.getName(), e.getMessage());
        }
    }

    private String summarizeRawInfo(SearchResult result, TrackedTech tech) {
        StringBuilder raw = new StringBuilder();
        raw.append("技术: ").append(tech.getName()).append("\n");
        raw.append("检测模式: ").append(result.getUpdateMode()).append("\n");
        raw.append("检测到的版本: ").append(result.getDetectedVersion()).append("\n");

        if (result.getReleaseInfo() != null && result.getReleaseInfo().getBody() != null) {
            String body = result.getReleaseInfo().getBody();
            raw.append("Release Notes:\n").append(
                    body.length() > 2000 ? body.substring(0, 2000) : body).append("\n");
        }

        if (!result.getRecentCommits().isEmpty()) {
            raw.append("近期 Commits (").append(result.getRecentCommits().size()).append(" 条):\n");
            for (GitHubCommitInfo c : result.getRecentCommits()) {
                String shortSha = c.getSha().length() > 7 ? c.getSha().substring(0, 7) : c.getSha();
                String firstLine = c.getMessage() != null ? c.getMessage().split("\n")[0] : "";
                raw.append("- ").append(shortSha).append(": ").append(firstLine).append("\n");
            }
        }

        if (result.getMergedPrs30Days() != null || result.getClosedIssues30Days() != null) {
            raw.append("近30日活跃度: ");
            if (result.getMergedPrs30Days() != null) {
                raw.append("Merged PRs=").append(result.getMergedPrs30Days()).append(" ");
            }
            if (result.getClosedIssues30Days() != null) {
                raw.append("Closed Issues=").append(result.getClosedIssues30Days());
            }
            raw.append("\n");
        }

        if (result.getRssContent() != null && !result.getRssContent().isBlank()) {
            raw.append(result.getRssContent()).append("\n");
        }

        if (result.getOfficialUrlContent() != null && !result.getOfficialUrlContent().isBlank()) {
            raw.append(result.getOfficialUrlContent()).append("\n");
        }

        // 官网更新日志页内容
        if (result.getOfficialChangelogContent() != null && !result.getOfficialChangelogContent().isBlank()) {
            raw.append(result.getOfficialChangelogContent()).append("\n");
        }

        try {
            // 避免 String.format / formatted：原始内容可能包含 %，会触发 Formatter 异常
            String prompt = "请用 3-5 句话简洁总结以下技术更新的原始信息，提取核心要点:\n" + raw;
            return chatModel.chat(prompt);
        } catch (Exception e) {
            log.warn("Failed to summarize raw info for {}", tech.getName());
            return raw.toString();
        }
    }

    private DataSufficiency evaluateDataSufficiency(SearchResult result) {
        if ("RELEASE".equals(result.getUpdateMode()) && result.getReleaseInfo() != null) {
            String body = result.getReleaseInfo().getBody();
            if (body != null && body.length() > 100) {
                return DataSufficiency.HIGH;
            }
            return DataSufficiency.MEDIUM;
        }
        if ("TAG".equals(result.getUpdateMode())) {
            return DataSufficiency.MEDIUM;
        }
        if ("COMMIT".equals(result.getUpdateMode())) {
            return result.getRecentCommits().size() >= 5
                    ? DataSufficiency.MEDIUM : DataSufficiency.LOW;
        }
        // P0-I: RSS 和官方URL 来源，有版本号定为 MEDIUM，否则 LOW
        if ("RSS".equals(result.getUpdateMode())) {
            return result.getDetectedVersion() != null
                    ? DataSufficiency.MEDIUM : DataSufficiency.LOW;
        }
        if ("OFFICIAL_URL".equals(result.getUpdateMode())) {
            // 官方页面只能提取版本号，内容较少，定为 LOW
            return DataSufficiency.LOW;
        }
        // 官网更新日志页：如果能提取到内容丰富的条目，可评为 HIGH 或 MEDIUM
        if ("OFFICIAL_CHANGELOG".equals(result.getUpdateMode())) {
            if (result.getChangelogEntries() != null && !result.getChangelogEntries().isEmpty()) {
                OfficialSiteCrawler.ChangelogEntry first = result.getChangelogEntries().get(0);
                // 内容足够丰富 & 有版本号 → HIGH；有版本号但内容一般 → MEDIUM；否则 LOW
                if (first.getContent() != null && first.getContent().length() > 300
                        && first.getVersion() != null) {
                    return DataSufficiency.HIGH;
                }
                if (first.getVersion() != null || first.getContent().length() > 100) {
                    return DataSufficiency.MEDIUM;
                }
            }
            return DataSufficiency.LOW;
        }
        return DataSufficiency.LOW;
    }
}
