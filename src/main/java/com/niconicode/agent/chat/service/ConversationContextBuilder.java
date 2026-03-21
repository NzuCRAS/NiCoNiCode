package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.agent.chat.dto.ConversationContext;
import com.niconicode.auth.entity.User;
import com.niconicode.auth.mapper.UserMapper;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.conversation.entity.SessionKeyContent;
import com.niconicode.conversation.entity.UserMemory;
import com.niconicode.conversation.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 上下文构建服务 — 一次性查询并组装 ConversationContext，
 * 避免 buildMessages 和 buildConversationContext 各自查 DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextBuilder {

    private final UserMapper userMapper;
    private final MemoryService memoryService;
    private final ChatMessageMapper messageMapper;
    private final UserMemoryService userMemoryService;
    private final SessionKeyContentService sessionKeyContentService;
    private final RagCacheService ragCacheService;
    private final RagService ragService;

    public ConversationContext build(Long userId, ChatSession session) {
        // 1. 查用户信息（单次 selectById）
        User user = userMapper.selectById(userId);

        // 2. 查最近消息（复用于 messages 构建和 context 构建）
        List<ChatMessage> recent = memoryService.getRecentMessages(session.getId());

        // 3. 查活跃消息总数
        long count = messageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .isNull(ChatMessage::getDeletedAt));

        // 4. 加载用户级记忆（索引查询，< 5ms）
        List<UserMemory> userMemories = userMemoryService.getUserMemories(userId);

        // 5. 加载会话关键内容（索引查询，< 5ms）
        List<SessionKeyContent> keyContents = sessionKeyContentService.getSessionKeyContents(session.getId());

        // 6. 组装
        ConversationContext ctx = ConversationContext.builder()
                .sessionId(session.getId())
                .sessionTitle(session.getTitle())
                .userId(userId)
                .nickname(user != null ? user.getNickname() : null)
                .summary(session.getSummary())
                .recentMessages(recent)
                .totalActiveMessageCount(count)
                .userMemories(userMemories)
                .sessionKeyContents(keyContents)
                .build();

        // 7. 新会话预热缓存：异步预检索用户常问话题
        if (count == 0 && userMemories != null) {
            preheatCacheAsync(userMemories);
        }

        return ctx;
    }

    /**
     * 新会话预热：异步预检索用户 FREQUENTLY_ASKED 记忆对应的知识
     */
    private void preheatCacheAsync(List<UserMemory> userMemories) {
        CompletableFuture.runAsync(() -> {
            try {
                userMemories.stream()
                        .filter(m -> "FREQUENTLY_ASKED".equals(m.getMemoryType())
                                && m.getContent() != null && !m.getContent().isBlank())
                        .limit(3)
                        .forEach(mem -> {
                            try {
                                // 预检索并自动缓存到 RagCacheService
                                ragService.retrieveContext(mem.getContent(), null);
                                log.debug("[Preheat] Pre-fetched RAG for: {}", mem.getContent());
                            } catch (Exception e) {
                                log.debug("[Preheat] Failed for: {}", mem.getContent());
                            }
                        });
            } catch (Exception e) {
                log.debug("[Preheat] Cache preheat failed: {}", e.getMessage());
            }
        });
    }
}
