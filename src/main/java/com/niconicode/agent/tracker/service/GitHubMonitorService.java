package com.niconicode.agent.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.tracker.dto.GitHubReleaseInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
}
