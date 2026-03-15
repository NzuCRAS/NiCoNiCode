package com.niconicode.agent.tracker.agent;

import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.GitHubMonitorService;
import com.niconicode.agent.chat.service.TraceLogger;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 信息搜集 Agent
 * 从多个数据源全面搜集技术更新信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAgent {

    private final GitHubMonitorService githubMonitor;
    private final ChatLanguageModel chatModel;
    private final TraceLogger traceLogger;

    public enum DataSufficiency { HIGH, MEDIUM, LOW }

    @Data
    public static class SearchResult {
        private boolean hasUpdate;
        private String detectedVersion;
        private String updateMode;        // RELEASE, TAG, COMMIT
        private GitHubReleaseInfo releaseInfo;
        private String tagInfo;
        private List<GitHubCommitInfo> recentCommits = new ArrayList<>();
        private String rawInfoSummary;
        private DataSufficiency dataSufficiency = DataSufficiency.LOW;
        private List<String> sourceUrls = new ArrayList<>();
    }

    public SearchResult search(TrackedTech tech, TraceLogger.TraceContext traceCtx) {
        SearchResult result = new SearchResult();

        if (tech.getGithubRepo() == null || tech.getGithubRepo().isBlank()) {
            result.setHasUpdate(false);
            return result;
        }

        String mode = tech.getTrackingMode() != null ? tech.getTrackingMode() : "RELEASE";
        traceLogger.trace(traceCtx, "SEARCH_MODE", "mode=" + mode);

        switch (mode) {
            case "RELEASE" -> searchReleaseMode(tech, result, traceCtx);
            case "TAG" -> searchTagMode(tech, result, traceCtx);
            case "COMMIT" -> searchCommitMode(tech, result, traceCtx);
            default -> searchReleaseMode(tech, result, traceCtx);
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

        try {
            String prompt = """
                    请用 3-5 句话简洁总结以下技术更新的原始信息，提取核心要点:
                    %s
                    """.formatted(raw.toString());
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
        return DataSufficiency.LOW;
    }
}
