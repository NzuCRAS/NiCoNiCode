package com.niconicode.agent.chat.service;

import com.niconicode.common.service.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * RAG 查询结果缓存 — 精确匹配缓存。
 * key = nico:rag:{sha256(normalize(query))}, TTL = 30min
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCacheService {

    private static final Duration RAG_CACHE_TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "rag:";

    private final CacheService cacheService;

    public String getCached(String query) {
        String key = buildKey(query);
        String cached = cacheService.get(key);
        if (cached != null) {
            log.debug("[RagCache] HIT for query: {}", truncate(query));
        }
        return cached;
    }

    public void put(String query, String result) {
        if (result == null || result.isBlank()) return;
        String key = buildKey(query);
        cacheService.set(key, result, RAG_CACHE_TTL);
        log.debug("[RagCache] PUT for query: {}, resultLen={}", truncate(query), result.length());
    }

    private String buildKey(String query) {
        String normalized = normalize(query);
        return KEY_PREFIX + sha256(normalized);
    }

    static String normalize(String query) {
        if (query == null) return "";
        return query.toLowerCase().trim().replaceAll("\\s+", " ");
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
            // fallback: use hashCode
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
