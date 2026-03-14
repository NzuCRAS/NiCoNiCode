package com.niconicode.agent.chat.mcp;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Configuration
public class LangChain4jConfig {

    // Primary model
    @Value("${ai.model.base-url}")
    private String baseUrl;
    @Value("${ai.model.api-key}")
    private String apiKey;
    @Value("${ai.model.model-name}")
    private String modelName;
    @Value("${ai.model.timeout:120}")
    private int timeout;

    // Fallback model
    @Value("${ai.fallback-model.base-url:${ai.model.base-url}}")
    private String fallbackBaseUrl;
    @Value("${ai.fallback-model.api-key:${ai.model.api-key}}")
    private String fallbackApiKey;
    @Value("${ai.fallback-model.model-name:${ai.model.model-name}}")
    private String fallbackModelName;
    @Value("${ai.fallback-model.timeout:${ai.model.timeout}}")
    private int fallbackTimeout;

    // Circuit breaker
    @Value("${ai.circuit-breaker.failure-threshold:3}")
    private int failureThreshold;
    @Value("${ai.circuit-breaker.reset-timeout:60}")
    private int resetTimeout;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> circuitOpenSince = new AtomicReference<>(null);

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        ChatLanguageModel primary = buildChatModel(baseUrl, apiKey, modelName, timeout);
        ChatLanguageModel fallback = buildChatModel(fallbackBaseUrl, fallbackApiKey, fallbackModelName, fallbackTimeout);

        return new CircuitBreakerChatModel(primary, fallback);
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        // Streaming 模型暂不做熔断（SSE 错误处理在 ChatService 中）
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(timeout))
                .temperature(0.7)
                .build();
    }

    private ChatLanguageModel buildChatModel(String url, String key, String model, int timeoutSec) {
        return OpenAiChatModel.builder()
                .baseUrl(url)
                .apiKey(key)
                .modelName(model)
                .timeout(Duration.ofSeconds(timeoutSec))
                .temperature(0.7)
                .build();
    }

    /**
     * 带简单熔断的 ChatLanguageModel 代理
     * 连续失败 N 次后切换到 fallback，resetTimeout 秒后自动恢复
     */
    private class CircuitBreakerChatModel implements ChatLanguageModel {
        private final ChatLanguageModel primary;
        private final ChatLanguageModel fallback;

        CircuitBreakerChatModel(ChatLanguageModel primary, ChatLanguageModel fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public dev.langchain4j.model.chat.response.ChatResponse chat(
                dev.langchain4j.model.chat.request.ChatRequest request) {
            if (isCircuitOpen()) {
                log.debug("Circuit breaker OPEN, using fallback model: {}", fallbackModelName);
                try {
                    dev.langchain4j.model.chat.response.ChatResponse resp = fallback.chat(request);
                    // fallback 成功，尝试半开状态
                    return resp;
                } catch (Exception e) {
                    log.error("Fallback model also failed", e);
                    throw e;
                }
            }

            try {
                dev.langchain4j.model.chat.response.ChatResponse resp = primary.chat(request);
                consecutiveFailures.set(0);
                return resp;
            } catch (Exception e) {
                int failures = consecutiveFailures.incrementAndGet();
                log.warn("Primary model failed ({}/{}): {}", failures, failureThreshold, e.getMessage());

                if (failures >= failureThreshold) {
                    circuitOpenSince.set(Instant.now());
                    log.warn("Circuit breaker OPENED, switching to fallback model: {}", fallbackModelName);
                }

                // 使用 fallback 重试
                try {
                    return fallback.chat(request);
                } catch (Exception fallbackEx) {
                    log.error("Both primary and fallback models failed", fallbackEx);
                    throw e; // 抛出原始异常
                }
            }
        }

        private boolean isCircuitOpen() {
            Instant openSince = circuitOpenSince.get();
            if (openSince == null) return false;

            if (Instant.now().isAfter(openSince.plusSeconds(resetTimeout))) {
                // 半开状态：重置并尝试 primary
                circuitOpenSince.set(null);
                consecutiveFailures.set(0);
                log.info("Circuit breaker RESET, trying primary model again");
                return false;
            }
            return true;
        }
    }
}
