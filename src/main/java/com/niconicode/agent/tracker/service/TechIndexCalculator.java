package com.niconicode.agent.tracker.service;

import com.niconicode.agent.tracker.entity.TrackedTech;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 技术指数计算器
 * 根据新的评分标准计算 0-1000 的技术指数：
 * - 基础规模（权重15%，150分）
 * - 当前热度（权重35%，350分）
 * - 社区生态（权重30%，300分）
 * - 代码活力（权重20%，200分）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TechIndexCalculator {

    private final GitHubMonitorService githubMonitor;

    /**
     * 计算完整的技术指数 (0-1000)
     */
    public int calculateTechIndex(TrackedTech tech) {
        if (tech.getGithubRepo() == null || tech.getGithubRepo().isBlank()) {
            return 500; // 无 GitHub 仓库，返回默认值
        }

        try {
            // 1. 获取仓库基础统计（1次 API 调用）
            GitHubMonitorService.GitHubRepoStats repoStats = githubMonitor.getRepoStats(tech.getGithubRepo());

            // 2. 传入已有的 starCount，避免 getStarDelta30Days 内部重复请求 /repos/{repo}
            int starDelta30Days = githubMonitor.getStarDelta30Days(tech.getGithubRepo(), repoStats.getStarCount());

            int contributorCount = githubMonitor.getContributorCount(tech.getGithubRepo());
            GitHubMonitorService.IssueAndPRStats issuePRStats = githubMonitor.getIssueAndPRStats30Days(tech.getGithubRepo());
            long avgIssueResponseHours = githubMonitor.getAverageIssueResponseTime(tech.getGithubRepo());

            // 计算各维度分数
            int basicScore = calculateBasicScale(repoStats.getStarCount(), repoStats.getForkCount());
            int hotnessScore = calculateCurrentHotness(repoStats.getWatchCount(), starDelta30Days, 100); // 简化下载量估算
            int communityScore = calculateCommunityEcosystem(contributorCount, issuePRStats, avgIssueResponseHours);
            int activityScore = calculateCodeActivity(repoStats, tech.getGithubRepo());

            // 加权计算总分
            int totalScore = (int) (basicScore * 0.15 + hotnessScore * 0.35 + communityScore * 0.30 + activityScore * 0.20);

            log.debug("TechIndex for {}: basic={}, hotness={}, community={}, activity={}, total={}",
                    tech.getName(), basicScore, hotnessScore, communityScore, activityScore, totalScore);

            return Math.max(0, Math.min(1000, totalScore));
        } catch (Exception e) {
            log.warn("Failed to calculate tech index for {}, using default 500", tech.getName(), e);
            return 500;
        }
    }

    /**
     * 基础规模评分 (0-150)
     * - Star数：max 80 分，使用对数函数 min(80, 20 * log10(star+1))
     * - Fork数：max 70 分，使用对数函数 min(70, 17.5 * log10(fork+1))
     */
    private int calculateBasicScale(int starCount, int forkCount) {
        int starScore = (int) Math.min(80, 20 * Math.log10(starCount + 1));
        int forkScore = (int) Math.min(70, 17.5 * Math.log10(forkCount + 1));
        return starScore + forkScore;
    }

    /**
     * 当前热度评分 (0-350)
     * - Watch数：max 120 分，使用对数函数 min(120, 30 * log10(watch+1))
     * - 30日Star增速：max 150 分，分段计分
     * - 30日下载量：max 80 分，使用对数函数 min(80, 20 * log10(downloads+1))
     */
    private int calculateCurrentHotness(int watchCount, int starDelta30Days, int downloads) {
        int watchScore = (int) Math.min(120, 30 * Math.log10(watchCount + 1));
        int starDeltaScore = calculateStarDeltaScore(starDelta30Days);
        int downloadScore = (int) Math.min(80, 20 * Math.log10(downloads + 1));
        return watchScore + starDeltaScore + downloadScore;
    }

    private int calculateStarDeltaScore(int delta) {
        if (delta > 2000) return 150;
        if (delta >= 1000) return 120;
        if (delta >= 500) return 80;
        if (delta >= 200) return 40;
        if (delta >= 50) return 15;
        return 5;
    }

    /**
     * 社区生态评分 (0-300)
     * - 贡献者数：max 100 分，使用对数函数 min(100, 25 * log10(contributors+1))
     * - 30日Issue/PR活跃度：max 120 分，分段计分
     * - 最近Issue响应时间：max 80 分，按时间分段
     */
    private int calculateCommunityEcosystem(int contributorCount, GitHubMonitorService.IssueAndPRStats stats, long avgResponseHours) {
        int contributorScore = (int) Math.min(100, 25 * Math.log10(contributorCount + 1));
        int activityScore = calculateIssueActivityScore(stats.getClosedIssues() + stats.getMergedPRs());
        int responseTimeScore = calculateResponseTimeScore(avgResponseHours);
        return contributorScore + activityScore + responseTimeScore;
    }

    private int calculateIssueActivityScore(int total) {
        if (total > 100) return 120;
        if (total >= 50) return 80;
        if (total >= 20) return 40;
        if (total >= 5) return 20;
        return 5;
    }

    private int calculateResponseTimeScore(long hours) {
        if (hours < 24) return 80;
        if (hours < 72) return 50;
        if (hours < 168) return 20;
        return 0;
    }

    /**
     * 代码活力评分 (0-200)
     * - 最近Commit频率：max 120 分，分段计分（按30日平均每日commit数）
     * - 最近Release：max 80 分，按版本类型
     */
    private int calculateCodeActivity(GitHubMonitorService.GitHubRepoStats stats, String repo) {
        int commitScore = calculateCommitFrequencyScore(repo);
        int releaseScore = calculateReleaseScore(repo);
        return commitScore + releaseScore;
    }

    private int calculateCommitFrequencyScore(String repo) {
        try {
            String since30DaysAgo = java.time.LocalDateTime.now().minusDays(30)
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00Z";
            var commits = githubMonitor.getCommitsSince(repo, since30DaysAgo, 1000);
            double dailyAverage = commits.size() / 30.0;

            if (dailyAverage > 2) return 120;
            if (dailyAverage >= 1) return 80;
            if (dailyAverage >= 0.5) return 40;
            if (dailyAverage >= 0.1) return 15;
            return 5;
        } catch (Exception e) {
            log.debug("Failed to calculate commit frequency score", e);
            return 20; // 默认分数
        }
    }

    private int calculateReleaseScore(String repo) {
        try {
            // 查询近90天内的 Release 数量（最多扫描10条，足够覆盖90天窗口）
            int recentReleases = githubMonitor.getRecentReleasesCount(repo, 90);
            if (recentReleases >= 3) return 80;   // 近3个月3次以上发布：活跃维护
            if (recentReleases == 2) return 60;   // 2次发布：正常维护
            if (recentReleases == 1) return 40;   // 1次发布：偶尔维护（原硬编码值）
            return 10;                             // 90天内无发布：长期未更新
        } catch (Exception e) {
            return 20; // 默认分数（不能确定，给一个保守中间值）
        }
    }
}
