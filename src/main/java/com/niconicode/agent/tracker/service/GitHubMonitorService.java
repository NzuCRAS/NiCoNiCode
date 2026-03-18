package com.niconicode.agent.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class GitHubMonitorService {

    private final RestTemplate restTemplate;
    private final String githubBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GitHubMonitorService(RestTemplate restTemplate,
                                @Value("${github.api.base-url}") String githubBaseUrl) {
        this.restTemplate = restTemplate;
        this.githubBaseUrl = githubBaseUrl;
    }

    private HttpHeaders createGetHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        return headers;
    }

    /**
     * 检查 GitHub 仓库最新 release
     * @return 最新版本号，或 null 如果没有变化
     */
    public String checkLatestRelease(String repo, String lastKnownVersion) {
        String url = githubBaseUrl + "/repos/" + repo + "/releases/latest";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);

            JsonNode body = objectMapper.readTree(resp.getBody());
            String tagName = body.get("tag_name").asText();

            if (lastKnownVersion != null && lastKnownVersion.equals(tagName)) {
                return null; // 没有新版本
            }
            return tagName;
        } catch (Exception e) {
            log.warn("Failed to check release for {}: {}", repo, e.getMessage());
            return null;
        }
    }

    public String getReleaseNotes(String repo, String tag) {
        String url = githubBaseUrl + "/repos/" + repo + "/releases/tags/" + tag;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode body = objectMapper.readTree(resp.getBody());
            return body.has("body") ? body.get("body").asText() : "";
        } catch (Exception e) {
            log.warn("Failed to get release notes for {} {}", repo, tag);
            return "";
        }
    }

    /**
     * 获取完整的 Release 信息（含发布日期、页面链接等）
     */
    public GitHubReleaseInfo getFullReleaseInfo(String repo, String tag) {
        String url = githubBaseUrl + "/repos/" + repo + "/releases/tags/" + tag;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode body = objectMapper.readTree(resp.getBody());

            GitHubReleaseInfo info = new GitHubReleaseInfo();
            info.setTagName(body.has("tag_name") ? body.get("tag_name").asText() : tag);
            info.setBody(body.has("body") ? body.get("body").asText() : "");
            info.setHtmlUrl(body.has("html_url") ? body.get("html_url").asText() : "");
            info.setPublishedAt(body.has("published_at") ? body.get("published_at").asText() : "");
            return info;
        } catch (Exception e) {
            log.warn("Failed to get full release info for {} {}: {}", repo, tag, e.getMessage());
            GitHubReleaseInfo fallback = new GitHubReleaseInfo();
            fallback.setTagName(tag);
            fallback.setBody("");
            fallback.setHtmlUrl("");
            fallback.setPublishedAt("");
            return fallback;
        }
    }

    /**
     * 检查最新 Tag（用于无 Release 的仓库）
     * @return 最新 tag name，或 null 如果没有变化
     */
    public String checkLatestTag(String repo, String lastKnownVersion) {
        String url = githubBaseUrl + "/repos/" + repo + "/tags?per_page=1";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);

            JsonNode body = objectMapper.readTree(resp.getBody());
            if (!body.isArray() || body.isEmpty()) return null;

            String tagName = body.get(0).get("name").asText();
            if (lastKnownVersion != null && lastKnownVersion.equals(tagName)) {
                return null;
            }
            return tagName;
        } catch (Exception e) {
            log.warn("Failed to check tags for {}: {}", repo, e.getMessage());
            return null;
        }
    }

    /**
     * 检查最新 Commit
     * @return GitHubCommitInfo，或 null 如果没有变化
     */
    public GitHubCommitInfo checkLatestCommit(String repo, String lastKnownSha) {
        String url = githubBaseUrl + "/repos/" + repo + "/commits?per_page=1";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);

            JsonNode body = objectMapper.readTree(resp.getBody());
            if (!body.isArray() || body.isEmpty()) return null;

            JsonNode node = body.get(0);
            String sha = node.get("sha").asText();
            if (lastKnownSha != null && lastKnownSha.equals(sha)) {
                return null;
            }

            return parseCommitNode(node);
        } catch (Exception e) {
            log.warn("Failed to check commits for {}: {}", repo, e.getMessage());
            return null;
        }
    }

    /**
     * 获取指定日期之后的 Commit 列表
     */
    public List<GitHubCommitInfo> getCommitsSince(String repo, String sinceDate, int perPage) {
        String url = githubBaseUrl + "/repos/" + repo + "/commits?since=" + sinceDate + "&per_page=" + perPage;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);

            JsonNode body = objectMapper.readTree(resp.getBody());
            List<GitHubCommitInfo> commits = new ArrayList<>();
            if (body.isArray()) {
                for (JsonNode node : body) {
                    commits.add(parseCommitNode(node));
                }
            }
            return commits;
        } catch (Exception e) {
            log.warn("Failed to get commits since {} for {}: {}", sinceDate, repo, e.getMessage());
            return List.of();
        }
    }

    private GitHubCommitInfo parseCommitNode(JsonNode node) {
        GitHubCommitInfo info = new GitHubCommitInfo();
        info.setSha(node.get("sha").asText());
        info.setHtmlUrl(node.has("html_url") ? node.get("html_url").asText() : "");

        JsonNode commit = node.get("commit");
        if (commit != null) {
            info.setMessage(commit.has("message") ? commit.get("message").asText() : "");
            JsonNode author = commit.get("author");
            if (author != null) {
                info.setAuthorName(author.has("name") ? author.get("name").asText() : "");
                info.setDate(author.has("date") ? author.get("date").asText() : "");
            }
        }
        return info;
    }

    /**
     * 获取仓库统计数据（用于技术指数评分）
     */
    public GitHubRepoStats getRepoStats(String repo) {
        String url = githubBaseUrl + "/repos/" + repo;
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode body = objectMapper.readTree(resp.getBody());

            GitHubRepoStats stats = new GitHubRepoStats();
            stats.setStarCount(body.has("stargazers_count") ? body.get("stargazers_count").asInt() : 0);
            stats.setForkCount(body.has("forks_count") ? body.get("forks_count").asInt() : 0);
            stats.setWatchCount(body.has("watchers_count") ? body.get("watchers_count").asInt() : 0);
            stats.setOpenIssues(body.has("open_issues_count") ? body.get("open_issues_count").asInt() : 0);
            stats.setLanguage(body.has("language") ? body.get("language").asText() : "");
            return stats;
        } catch (Exception e) {
            log.warn("Failed to get repo stats for {}: {}", repo, e.getMessage());
            return new GitHubRepoStats();
        }
    }

    /**
     * 获取近30日内新增的 Star 数量（估算）。
     * <p>
     * GitHub API 不直接提供历史 Star 快照，此处通过扫描最近的 WatchEvent
     * 来估算增量。每页100条，最多扫描3页（300条事件，约覆盖活跃仓库一周）。
     * 对于低活跃仓库事件数不足时，以 currentStars 的对数做保守估算。
     *
     * @param repo         仓库路径，如 "spring-projects/spring-boot"
     * @param currentStars 当前 Star 总数（调用方应从 getRepoStats 中传入，避免重复请求）
     */
    public int getStarDelta30Days(String repo, int currentStars) {
        try {
            java.time.Instant cutoff = java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
            int watchEventCount = 0;
            boolean reachedCutoff = false;

            // 最多扫描3页事件（每页100条，共300条），防止 API 消耗过多
            for (int page = 1; page <= 3 && !reachedCutoff; page++) {
                String url = githubBaseUrl + "/repos/" + repo + "/events?per_page=100&page=" + page;
                try {
                    ResponseEntity<String> resp = restTemplate.exchange(
                            url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
                    JsonNode events = objectMapper.readTree(resp.getBody());
                    if (!events.isArray() || events.isEmpty()) break;

                    for (JsonNode event : events) {
                        String type = event.has("type") ? event.get("type").asText() : "";
                        String createdAt = event.has("created_at") ? event.get("created_at").asText() : "";
                        if (!createdAt.isEmpty()) {
                            java.time.Instant eventTime = java.time.Instant.parse(createdAt);
                            if (eventTime.isBefore(cutoff)) {
                                reachedCutoff = true;
                                break;
                            }
                        }
                        if ("WatchEvent".equals(type)) {
                            watchEventCount++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to fetch events page {} for {}", page, repo);
                    break;
                }
            }

            // 若扫描到的 WatchEvent 数量可信（非零），直接返回
            if (watchEventCount > 0 || reachedCutoff) {
                return watchEventCount;
            }

            // 降级：对数估算（低活跃仓库 events 接口可能为空或受限）
            // 公式：sqrt(currentStars) * 0.5，即大项目估算增量更多，但上限500
            return (int) Math.min(500, Math.sqrt(currentStars) * 0.5);

        } catch (Exception e) {
            log.warn("Failed to calculate star delta for {}: {}", repo, e.getMessage());
            return 0;
        }
    }

    /**
     * @deprecated 请使用 {@link #getStarDelta30Days(String, int)} 以避免重复请求 /repos/{repo}
     */
    @Deprecated
    public int getStarDelta30Days(String repo) {
        try {
            String url = githubBaseUrl + "/repos/" + repo;
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode body = objectMapper.readTree(resp.getBody());
            int currentStars = body.has("stargazers_count") ? body.get("stargazers_count").asInt() : 0;
            return getStarDelta30Days(repo, currentStars);
        } catch (Exception e) {
            log.warn("Failed to calculate star delta for {}: {}", repo, e.getMessage());
            return 0;
        }
    }

    /**
     * 获取近 N 天内发布的 Release 数量（用于代码活力评分）。
     * 请求 /releases?per_page=10 并统计 published_at 在时间窗口内的条目数。
     *
     * @param repo    仓库路径
     * @param days    时间窗口（天）
     */
    public int getRecentReleasesCount(String repo, int days) {
        String url = githubBaseUrl + "/repos/" + repo + "/releases?per_page=10";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode releases = objectMapper.readTree(resp.getBody());
            if (!releases.isArray()) return 0;

            java.time.Instant cutoff = java.time.Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
            int count = 0;
            for (JsonNode release : releases) {
                String publishedAt = release.has("published_at") ? release.get("published_at").asText() : "";
                if (!publishedAt.isEmpty()) {
                    try {
                        if (java.time.Instant.parse(publishedAt).isAfter(cutoff)) {
                            count++;
                        }
                    } catch (Exception ignored) {}
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("Failed to get recent releases count for {}: {}", repo, e.getMessage());
            return 0;
        }
    }

    /**
     * 获取仓库贡献者数量
     */
    public int getContributorCount(String repo) {
        String url = githubBaseUrl + "/repos/" + repo + "/contributors?per_page=1";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            // 从 Link header 中解析总数
            HttpHeaders headers = resp.getHeaders();
            String linkHeader = headers.getFirst("Link");
            if (linkHeader != null && linkHeader.contains("last")) {
                String[] parts = linkHeader.split(",");
                for (String part : parts) {
                    if (part.contains("last")) {
                        String lastUrl = part.substring(part.indexOf('<') + 1, part.indexOf('>'));
                        if (lastUrl.contains("page=")) {
                            String page = lastUrl.substring(lastUrl.lastIndexOf("page=") + 5);
                            page = page.split("&")[0];
                            return Integer.parseInt(page);
                        }
                    }
                }
            }
            // 如果无法解析，返回响应体数组长度
            JsonNode body = objectMapper.readTree(resp.getBody());
            return body.isArray() ? body.size() : 0;
        } catch (Exception e) {
            log.warn("Failed to get contributor count for {}: {}", repo, e.getMessage());
            return 0;
        }
    }

    /**
     * 获取近30日内关闭的 Issue 和合并的 PR 数量
     */
    public IssueAndPRStats getIssueAndPRStats30Days(String repo) {
        IssueAndPRStats stats = new IssueAndPRStats();
        String since30DaysAgo = java.time.LocalDateTime.now().minusDays(30)
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00Z";

        try {
            // 获取关闭的 Issue 数
            String issueUrl = githubBaseUrl + "/search/issues?q=repo:" + repo + "+type:issue+is:closed+closed:>" + since30DaysAgo;
            ResponseEntity<String> issueResp = restTemplate.exchange(
                    issueUrl, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode issueBody = objectMapper.readTree(issueResp.getBody());
            stats.setClosedIssues(issueBody.has("total_count") ? issueBody.get("total_count").asInt() : 0);

            // 获取合并的 PR 数
            String prUrl = githubBaseUrl + "/search/issues?q=repo:" + repo + "+type:pr+is:merged+merged:>" + since30DaysAgo;
            ResponseEntity<String> prResp = restTemplate.exchange(
                    prUrl, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode prBody = objectMapper.readTree(prResp.getBody());
            stats.setMergedPRs(prBody.has("total_count") ? prBody.get("total_count").asInt() : 0);
        } catch (Exception e) {
            log.warn("Failed to get issue/PR stats for {}: {}", repo, e.getMessage());
        }

        return stats;
    }

    /**
     * 获取平均 Issue 响应时间
     */
    public long getAverageIssueResponseTime(String repo) {
        String url = githubBaseUrl + "/repos/" + repo + "/issues?state=closed&per_page=30&sort=created&direction=desc";
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(createGetHeaders()), String.class);
            JsonNode body = objectMapper.readTree(resp.getBody());
            if (!body.isArray() || body.isEmpty()) return 0;

            long totalTime = 0;
            int count = 0;
            for (JsonNode issue : body) {
                String createdAt = issue.has("created_at") ? issue.get("created_at").asText() : "";
                String closedAt = issue.has("closed_at") ? issue.get("closed_at").asText() : "";
                if (!createdAt.isEmpty() && !closedAt.isEmpty()) {
                    try {
                        java.time.Instant created = java.time.Instant.parse(createdAt);
                        java.time.Instant closed = java.time.Instant.parse(closedAt);
                        totalTime += java.time.Duration.between(created, closed).getSeconds();
                        count++;
                    } catch (Exception e) {
                        log.debug("Failed to parse dates for issue response time", e);
                    }
                }
            }
            return count > 0 ? (totalTime / count / 3600) : 0; // 返回小时数
        } catch (Exception e) {
            log.warn("Failed to get average issue response time for {}: {}", repo, e.getMessage());
            return 0;
        }
    }

    @lombok.Data
    public static class GitHubRepoStats {
        private int starCount;
        private int forkCount;
        private int watchCount;
        private int openIssues;
        private String language;
    }

    @lombok.Data
    public static class IssueAndPRStats {
        private int closedIssues;
        private int mergedPRs;
    }
}
