package com.niconicode.agent.chat.service;

import lombok.extern.slf4j.Slf4j;

/**
 * @deprecated 已弃用。全链路追踪已迁移到 TraceLogger，使用日志而非数据库记录。
 * 此类不再注册为 Spring Bean（已移除 @Service），保留仅供历史参考，可在下次清理时删除。
 */
@Slf4j
@Deprecated
public class ChatTraceService {

    public static final String STAGE_INTENT = "INTENT";
    public static final String STAGE_REWRITE = "REWRITE";
    public static final String STAGE_RETRIEVE = "RETRIEVE";
    public static final String STAGE_TOOL_CALL = "TOOL_CALL";
    public static final String STAGE_GENERATE = "GENERATE";
    public static final String STAGE_SUMMARY = "SUMMARY";

    /**
     * @deprecated 使用 TraceLogger 代替
     */
    @Deprecated
    public void trace(Long sessionId, Long messageId, String stage,
                      String input, String output, int durationMs) {
        // 不再做任何处理，日志由 TraceLogger 负责
        log.debug("Deprecated trace call for stage: {}", stage);
    }

    /**
     * @deprecated 使用 TraceLogger 代替
     */
    @Deprecated
    public TraceTimer start(Long sessionId, String stage, String input) {
        return new TraceTimer();
    }

    /**
     * 计时辅助类，已弃用
     */
    public static class TraceTimer implements AutoCloseable {
        @Override
        public void close() {
            // 无操作
        }

        public TraceTimer output(String output) {
            return this;
        }

        public TraceTimer messageId(Long messageId) {
            return this;
        }
    }
}

