package com.niconicode.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 统一缓存服务 — 封装 StringRedisTemplate，所有方法 try-catch 优雅降级。
 * Redis 不可用时静默返回 null/false，不影响业务。
 */
@Slf4j
@Service
public class CacheService {

    private static final String PREFIX = "nico:";

    private final StringRedisTemplate redis;
    private volatile boolean available = true;

    public CacheService(StringRedisTemplate redis) {
        this.redis = redis;
        try {
            redis.opsForValue().get(PREFIX + "ping");
        } catch (Exception e) {
            available = false;
            log.warn("Redis unavailable, CacheService will degrade silently: {}", e.getMessage());
        }
    }

    public String get(String key) {
        try {
            return redis.opsForValue().get(PREFIX + key);
        } catch (Exception e) {
            logOnce(e);
            return null;
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + key, value, ttl);
            available = true;
        } catch (Exception e) {
            logOnce(e);
        }
    }

    public void delete(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (Exception e) {
            logOnce(e);
        }
    }

    public boolean exists(String key) {
        try {
            Boolean b = redis.hasKey(PREFIX + key);
            return Boolean.TRUE.equals(b);
        } catch (Exception e) {
            logOnce(e);
            return false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private void logOnce(Exception e) {
        if (available) {
            available = false;
            log.warn("Redis operation failed, degrading: {}", e.getMessage());
        }
    }
}
