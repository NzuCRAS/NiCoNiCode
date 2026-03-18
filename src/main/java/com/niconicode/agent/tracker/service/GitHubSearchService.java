package com.niconicode.agent.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 GitHub Search API 的仓库检索服务。
 * 用途：当 tracked tech 只有 name、缺少 githubRepo 时，尝试自动补全 owner/repo。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubSearchService {

    private final RestTemplate restTemplate;

    @Value("${github.api.base-url}")
    private String githubBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpHeaders createGetHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        return headers;
    }

    /**
     * 根据技术名搜索最相关的 GitHub 仓库，并按 star 降序选择第一名。
     *
     * @param techName 技术名，如 "Spring Boot" / "Vue" / "langchain4j"
     * @return owner/repo；找不到返回 null
     */
    public RepoCandidate findBestRepoByName(String techName) {
        if (techName == null || techName.isBlank()) return null;

        // 注意：这里只做“自动补全”，不追求 100% 准确。管理员可后续修正。
        String q = techName.trim();
        String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String url = githubBaseUrl + "/search/repositories?q=" + encoded + "&sort=stars&order=desc&per_page=5";

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createGetHeaders()),
                    String.class
            );

            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) return null;

            JsonNode best = items.get(0);
            RepoCandidate c = new RepoCandidate();
            c.setFullName(best.has("full_name") ? best.get("full_name").asText() : null);
            c.setHtmlUrl(best.has("html_url") ? best.get("html_url").asText() : null);
            c.setDescription(best.has("description") ? best.get("description").asText("") : "");
            c.setStars(best.has("stargazers_count") ? best.get("stargazers_count").asInt(0) : 0);
            c.setLanguage(best.has("language") ? best.get("language").asText("") : "");
            return c.getFullName() != null && !c.getFullName().isBlank() ? c : null;
        } catch (Exception e) {
            log.warn("Failed to search best repo for '{}': {}", techName, e.getMessage());
            return null;
        }
    }

    /**
     * 返回 Top-N 候选，便于后续做二次判别或提供给管理员选择。
     */
    public List<RepoCandidate> searchTopRepos(String techName, int limit) {
        if (techName == null || techName.isBlank()) return List.of();
        int perPage = Math.min(Math.max(limit, 1), 10);

        String q = techName.trim();
        String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String url = githubBaseUrl + "/search/repositories?q=" + encoded + "&sort=stars&order=desc&per_page=" + perPage;

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(createGetHeaders()),
                    String.class
            );
            JsonNode root = objectMapper.readTree(resp.getBody());
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) return List.of();

            List<RepoCandidate> out = new ArrayList<>();
            for (JsonNode it : items) {
                RepoCandidate c = new RepoCandidate();
                c.setFullName(it.has("full_name") ? it.get("full_name").asText() : null);
                if (c.getFullName() == null || c.getFullName().isBlank()) continue;
                c.setHtmlUrl(it.has("html_url") ? it.get("html_url").asText() : null);
                c.setDescription(it.has("description") ? it.get("description").asText("") : "");
                c.setStars(it.has("stargazers_count") ? it.get("stargazers_count").asInt(0) : 0);
                c.setLanguage(it.has("language") ? it.get("language").asText("") : "");
                out.add(c);
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to search repos for '{}': {}", techName, e.getMessage());
            return List.of();
        }
    }

    @Data
    public static class RepoCandidate {
        private String fullName; // owner/repo
        private String htmlUrl;
        private String description;
        private int stars;
        private String language;
    }
}
