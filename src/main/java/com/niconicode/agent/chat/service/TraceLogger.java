package com.niconicode.agent.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 全链路追踪日志记录
 *
 * 只为 AI 相关链路提供追踪（聊天系统、狗仔系统），不影响其他日志。
 * 使用独立的 traceId 参数传递，不依赖 MDC/ThreadLocal，天然支持跨线程。
 *
 * 日志输出示例：
 * [TRACE:550e8400] === TRACE_START === userId=123, sessionId=456
 * [TRACE:550e8400] [USER_MESSAGE] 如何使用Spring Boot...
 * [TRACE:550e8400] [INTENT_CLASSIFY] intent=KNOWLEDGE_QUERY, duration=175ms
 * [TRACE:550e8400] [TOOL_CALL] tool=searchKnowledge, input=..., output=..., duration=320ms
 * [TRACE:550e8400] [STREAM_COMPLETE] totalLength=1024, duration=5200ms
 * [TRACE:550e8400] === TRACE_END === totalDuration=6500ms
 */
@Slf4j
@Component
public class TraceLogger {

    /**
     * 生成新的 traceId 并记录追踪开始
     */
    public TraceContext startTrace(Long userId, Long sessionId) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();
        log.info("[TRACE:{}] === TRACE_START === userId={}, sessionId={}", traceId, userId, sessionId);
        return new TraceContext(traceId, startTime);
    }

    public void traceUserMessage(TraceContext ctx, String message) {
        log.info("[TRACE:{}] [USER_MESSAGE] {}", ctx.traceId, truncate(message, 200));
    }

    public void traceIntentClassification(TraceContext ctx, String intent, String subIntent,
                                          String classifiedBy, int durationMs) {
        log.info("[TRACE:{}] [INTENT_CLASSIFY] intent={}, subIntent={}, classifiedBy={}, duration={}ms",
                ctx.traceId, intent, subIntent, classifiedBy, durationMs);
    }

    public void traceQueryRewrite(TraceContext ctx, String original, String rewritten, int durationMs) {
        log.info("[TRACE:{}] [QUERY_REWRITE] original={} -> rewritten={}, duration={}ms",
                ctx.traceId, truncate(original, 100), truncate(rewritten, 100), durationMs);
    }

    public void traceQueryRewriteEnhanced(TraceContext ctx, String original, String rewritten,
                                          String strategy, int subTaskCount, int durationMs) {
        log.info("[TRACE:{}] [QUERY_REWRITE] original={} -> rewritten={}, strategy={}, subTasks={}, duration={}ms",
                ctx.traceId, truncate(original, 100), truncate(rewritten, 100),
                strategy, subTaskCount, durationMs);
    }

    public void traceQueryDag(TraceContext ctx, int totalTasks, int waves, int completedTasks, int durationMs) {
        log.info("[TRACE:{}] [DAG_EXEC] totalTasks={}, waves={}, completed={}, duration={}ms",
                ctx.traceId, totalTasks, waves, completedTasks, durationMs);
    }

    public void traceToolCall(TraceContext ctx, String toolName, String input, String output, int durationMs) {
        log.info("[TRACE:{}] [TOOL_CALL] tool={}, input={}, output={}, duration={}ms",
                ctx.traceId, toolName, truncate(input, 150), truncate(output, 150), durationMs);
    }

    public void traceRAGRetrieval(TraceContext ctx, String query, int docCount, List<String> docTitles, int durationMs) {
        String titles = docTitles.isEmpty() ? "none" : String.join("; ",
                docTitles.stream().limit(3).toList());
        log.info("[TRACE:{}] [RAG_RETRIEVE] query={}, docCount={}, docs=[{}], duration={}ms",
                ctx.traceId, truncate(query, 100), docCount, titles, durationMs);
    }

    public void traceStreamingStart(TraceContext ctx) {
        log.info("[TRACE:{}] [STREAM_START]", ctx.traceId);
    }

    public void traceStreamingComplete(TraceContext ctx, int totalLength, int durationMs) {
        log.info("[TRACE:{}] [STREAM_COMPLETE] totalLength={}, duration={}ms", ctx.traceId, totalLength, durationMs);
    }

    public void traceError(TraceContext ctx, String stage, Throwable error) {
        log.error("[TRACE:{}] [ERROR] stage={}, error={}", ctx.traceId, stage, error.getMessage(), error);
    }

    public void trace(TraceContext ctx, String stage, String message) {
        log.info("[TRACE:{}] [{}] {}", ctx.traceId, stage, message);
    }

    public void endTrace(TraceContext ctx) {
        long totalDuration = System.currentTimeMillis() - ctx.startTime;
        log.info("[TRACE:{}] === TRACE_END === totalDuration={}ms", ctx.traceId, totalDuration);
    }

    /**
     * 带计时的执行包装 — 自动记录耗时和异常
     */
    public <T> T traceExecution(TraceContext ctx, String stage, Supplier<T> action) {
        long start = System.currentTimeMillis();
        try {
            T result = action.get();
            int duration = (int)(System.currentTimeMillis() - start);
            trace(ctx, stage, "duration=" + duration + "ms");
            return result;
        } catch (Exception e) {
            traceError(ctx, stage, e);
            throw e;
        }
    }

    public void traceExecution(TraceContext ctx, String stage, Runnable action) {
        traceExecution(ctx, stage, () -> { action.run(); return null; });
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * 追踪上下文 — 不可变对象，可安全地跨线程传递
     */
    public static class TraceContext {
        public final String traceId;
        public final long startTime;

        TraceContext(String traceId, long startTime) {
            this.traceId = traceId;
            this.startTime = startTime;
        }
    }
}
