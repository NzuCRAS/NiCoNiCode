package com.niconicode.agent.chat.graph;

import com.niconicode.agent.chat.service.SessionKeyContentService;
import com.niconicode.agent.chat.service.TraceLogger;
import com.niconicode.agent.chat.service.UserMemoryService;
import com.niconicode.conversation.entity.SessionKeyContent;
import com.niconicode.conversation.entity.UserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆管道图 — 两个子图：
 * 1. 上下文加载图（同步，请求前调用）
 * 2. 记忆提取图（异步，响应后调用）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryPipelineGraph {

    private final UserMemoryService userMemoryService;
    private final SessionKeyContentService sessionKeyContentService;
    private final TraceLogger traceLogger;

    /**
     * 上下文加载图：LOAD_USER_MEMORY → LOAD_SESSION_KEY_CONTENT → MERGE_CONTEXT → END
     * 同步调用，< 10ms（纯索引查询）
     */
    public MemoryState executeContextLoading(MemoryState state) {
        StateGraph<MemoryState> graph = new StateGraph<MemoryState>()
                .addNode("LOAD_USER_MEMORY", this::loadUserMemory)
                .addNode("LOAD_SESSION_KEY_CONTENT", this::loadSessionKeyContent)
                .addNode("MERGE_CONTEXT", this::mergeContext)
                .addEdge("LOAD_USER_MEMORY", "LOAD_SESSION_KEY_CONTENT")
                .addEdge("LOAD_SESSION_KEY_CONTENT", "MERGE_CONTEXT")
                .addEdge("MERGE_CONTEXT", StateGraph.END)
                .setEntry("LOAD_USER_MEMORY");

        return graph.execute(state);
    }

    /**
     * 记忆提取图（异步，响应后调用）：
     * EXTRACT_KEY_CONTENT → UPDATE_SESSION_KEY_CONTENT → [条件: 每5条消息] → EXTRACT_USER_PROFILE → UPDATE_USER_MEMORY → END
     */
    public MemoryState executeMemoryExtraction(MemoryState state) {
        StateGraph<MemoryState> graph = new StateGraph<MemoryState>()
                .addNode("EXTRACT_KEY_CONTENT", this::extractKeyContent)
                .addNode("CHECK_PROFILE_EXTRACTION", this::checkProfileExtraction)
                .addNode("EXTRACT_USER_PROFILE", this::extractUserProfile)
                .addEdge("EXTRACT_KEY_CONTENT", "CHECK_PROFILE_EXTRACTION")
                // 条件边：需要提取用户画像时走 EXTRACT_USER_PROFILE
                .addConditionalEdge("CHECK_PROFILE_EXTRACTION", "EXTRACT_USER_PROFILE",
                        s -> !s.isSkipUserProfileExtraction())
                // 无条件边：跳过用户画像提取时直接结束
                .addEdge("CHECK_PROFILE_EXTRACTION", StateGraph.END)
                .addEdge("EXTRACT_USER_PROFILE", StateGraph.END)
                .setEntry("EXTRACT_KEY_CONTENT");

        return graph.execute(state);
    }

    // ---- 上下文加载节点 ----

    private MemoryState loadUserMemory(MemoryState state) {
        try {
            List<UserMemory> memories = userMemoryService.getUserMemories(state.getUserId());
            state.setUserMemories(memories);
            if (state.getTraceCtx() != null) {
                traceLogger.trace(state.getTraceCtx(), "LOAD_USER_MEMORY",
                        "loaded=" + memories.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load user memories for user {}: {}", state.getUserId(), e.getMessage());
        }
        return state;
    }

    private MemoryState loadSessionKeyContent(MemoryState state) {
        try {
            List<SessionKeyContent> contents =
                    sessionKeyContentService.getSessionKeyContents(state.getSessionId());
            state.setSessionKeyContents(contents);
            if (state.getTraceCtx() != null) {
                traceLogger.trace(state.getTraceCtx(), "LOAD_SESSION_KEY_CONTENT",
                        "loaded=" + contents.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load session key contents for session {}: {}",
                    state.getSessionId(), e.getMessage());
        }
        return state;
    }

    private MemoryState mergeContext(MemoryState state) {
        StringBuilder context = new StringBuilder();

        String userProfile = UserMemoryService.formatForContext(state.getUserMemories());
        if (!userProfile.isEmpty()) {
            context.append(userProfile).append("\n\n");
        }

        String keyContents = SessionKeyContentService.formatForContext(state.getSessionKeyContents());
        if (!keyContents.isEmpty()) {
            context.append(keyContents).append("\n\n");
        }

        state.setMergedContextForAI(context.toString().trim());

        if (state.getTraceCtx() != null) {
            traceLogger.trace(state.getTraceCtx(), "MERGE_CONTEXT",
                    "contextLength=" + state.getMergedContextForAI().length());
        }
        return state;
    }

    // ---- 记忆提取节点 ----

    private MemoryState extractKeyContent(MemoryState state) {
        try {
            sessionKeyContentService.extractKeyContent(
                    state.getSessionId(),
                    state.getCurrentUserMessage(),
                    state.getCurrentAiReply(),
                    null, // sourceMessageId 后续可补充
                    state.getTraceCtx());
        } catch (Exception e) {
            log.warn("Key content extraction node failed: {}", e.getMessage());
        }
        return state;
    }

    private MemoryState checkProfileExtraction(MemoryState state) {
        // 每5条消息触发一次用户画像提取
        int messageCount = state.getRecentMessages() != null ? state.getRecentMessages().size() : 0;
        state.setSkipUserProfileExtraction(messageCount % 5 != 0 || messageCount == 0);

        if (state.getTraceCtx() != null) {
            traceLogger.trace(state.getTraceCtx(), "CHECK_PROFILE_EXTRACTION",
                    "messageCount=" + messageCount + ", skip=" + state.isSkipUserProfileExtraction());
        }
        return state;
    }

    private MemoryState extractUserProfile(MemoryState state) {
        try {
            userMemoryService.extractAndSaveUserProfile(
                    state.getUserId(),
                    state.getSessionId(),
                    state.getRecentMessages(),
                    state.getTraceCtx());
        } catch (Exception e) {
            log.warn("User profile extraction node failed: {}", e.getMessage());
        }
        return state;
    }
}
