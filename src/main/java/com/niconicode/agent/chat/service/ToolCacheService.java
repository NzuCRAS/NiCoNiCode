package com.niconicode.agent.chat.service;

import com.niconicode.common.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 工具结果缓存 — 对幂等工具的结果进行 Redis 缓存。
 * 非幂等工具（有副作用或时间敏感）不缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCacheService {

    private static final String KEY_PREFIX = "tool:";

    private final CacheService cacheService;

    // 可缓存工具及其 TTL
    private static final Map<String, Duration> CACHEABLE_TOOLS = Map.of(
            "listTrackedTechnologies", Duration.ofMinutes(5),
            "getTechInfo", Duration.ofMinutes(5),
            "knowledgeSearch", Duration.ofMinutes(10),
            "searchReports", Duration.ofMinutes(5),
            "getRecentReportsForTech", Duration.ofMinutes(3)
    );

    // 不可缓存工具（时间敏感或有副作用）
    private static final Set<String> NON_CACHEABLE = Set.of(
            "getReportsByDate", "recordTechMention"
    );

    public boolean isCacheable(String toolName) {
        return CACHEABLE_TOOLS.containsKey(toolName);
    }

    public String getCached(String toolName, String arguments) {
        if (!isCacheable(toolName)) return null;
        String key = buildKey(toolName, arguments);
        String cached = cacheService.get(key);
        if (cached != null) {
            log.debug("[ToolCache] HIT: {}({})", toolName, truncate(arguments));
        }
        return cached;
    }

    public void put(String toolName, String arguments, String result) {
        if (!isCacheable(toolName) || result == null) return;
        Duration ttl = CACHEABLE_TOOLS.get(toolName);
        String key = buildKey(toolName, arguments);
        cacheService.set(key, result, ttl);
        log.debug("[ToolCache] PUT: {}({}), ttl={}s", toolName, truncate(arguments), ttl.getSeconds());
    }

    private String buildKey(String toolName, String arguments) {
        return KEY_PREFIX + toolName + ":" + sha256(arguments != null ? arguments : "");
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
