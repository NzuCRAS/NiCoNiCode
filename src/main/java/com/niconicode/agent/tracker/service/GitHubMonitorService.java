package com.niconicode.agent.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
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
}
