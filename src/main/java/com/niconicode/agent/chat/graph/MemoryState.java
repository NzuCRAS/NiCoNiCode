package com.niconicode.agent.chat.graph;

import com.niconicode.agent.chat.service.TraceLogger;
import com.niconicode.conversation.entity.SessionKeyContent;
import com.niconicode.conversation.entity.UserMemory;
import com.niconicode.conversation.entity.ChatMessage;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆管道状态对象 — 在图节点间传递的可变状态
 */
@Data
@Builder
public class MemoryState {
    // 输入
    private Long userId;
    private Long sessionId;
    private String currentUserMessage;
    private String currentAiReply;
    @Builder.Default
    private List<ChatMessage> recentMessages = new ArrayList<>();

    // 加载的记忆
    @Builder.Default
    private List<UserMemory> userMemories = new ArrayList<>();
    private String sessionSummary;
    @Builder.Default
    private List<SessionKeyContent> sessionKeyContents = new ArrayList<>();

    // 提取结果
    @Builder.Default
    private List<SessionKeyContent> extractedKeyContents = new ArrayList<>();
    private UserProfileExtraction userProfileExtraction;

    // 输出
    private String mergedContextForAI;

    // 控制
    private boolean skipUserProfileExtraction;
    private TraceLogger.TraceContext traceCtx;

    /**
     * 用户画像提取结果（中间产物）
     */
    @Data
    @Builder
    public static class UserProfileExtraction {
        @Builder.Default
        private List<ExtractedMemory> memories = new ArrayList<>();

        @Data
        @Builder
        public static class ExtractedMemory {
            private String type;
            private String content;
            private double confidence;
        }
    }
}
