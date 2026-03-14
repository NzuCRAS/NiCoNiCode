package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.conversation.entity.ChatTrace;
import com.niconicode.conversation.mapper.ChatTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全链路追踪服务
 * 记录聊天处理流程中各环节的输入、输出和耗时
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatTraceService {

    private final ChatTraceMapper traceMapper;

    public static final String STAGE_INTENT = "INTENT";
    public static final String STAGE_REWRITE = "REWRITE";
    public static final String STAGE_RETRIEVE = "RETRIEVE";
    public static final String STAGE_TOOL_CALL = "TOOL_CALL";
    public static final String STAGE_GENERATE = "GENERATE";
    public static final String STAGE_SUMMARY = "SUMMARY";

    /**
     * 异步记录 trace（不影响主流程性能）
     */
    @Async
    public void trace(Long sessionId, Long messageId, String stage,
                      String input, String output, int durationMs) {
        try {
            ChatTrace trace = new ChatTrace();
            trace.setSessionId(sessionId);
            trace.setMessageId(messageId);
            trace.setStage(stage);
            trace.setInput(truncate(input, 2000));
            trace.setOutput(truncate(output, 2000));
            trace.setDurationMs(durationMs);
            trace.setCreatedAt(LocalDateTime.now());
            traceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("Failed to save chat trace: {}", e.getMessage());
        }
    }

    /**
     * 简便方法：记录带计时的 trace
     */
    public TraceTimer start(Long sessionId, String stage, String input) {
        return new TraceTimer(this, sessionId, stage, input);
    }

    /**
     * 查询某个 session 的全部 trace（管理后台用）
     */
    public List<ChatTrace> getSessionTraces(Long sessionId) {
        return traceMapper.selectList(
                new LambdaQueryWrapper<ChatTrace>()
                        .eq(ChatTrace::getSessionId, sessionId)
                        .orderByAsc(ChatTrace::getCreatedAt));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    /**
     * 计时辅助类，使用 try-with-resources 自动记录耗时
     */
    public static class TraceTimer implements AutoCloseable {
        private final ChatTraceService service;
        private final Long sessionId;
        private final String stage;
        private final String input;
        private final long startTime;
        private String output;
        private Long messageId;

        TraceTimer(ChatTraceService service, Long sessionId, String stage, String input) {
            this.service = service;
            this.sessionId = sessionId;
            this.stage = stage;
            this.input = input;
            this.startTime = System.currentTimeMillis();
        }

        public TraceTimer output(String output) {
            this.output = output;
            return this;
        }

        public TraceTimer messageId(Long messageId) {
            this.messageId = messageId;
            return this;
        }

        @Override
        public void close() {
            int duration = (int) (System.currentTimeMillis() - startTime);
            service.trace(sessionId, messageId, stage, input, output, duration);
        }
    }
}
