package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.agent.chat.dto.ConversationContext;
import com.niconicode.auth.entity.User;
import com.niconicode.auth.mapper.UserMapper;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.conversation.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 上下文构建服务 — 一次性查询并组装 ConversationContext，
 * 避免 buildMessages 和 buildConversationContext 各自查 DB。
 */
@Service
@RequiredArgsConstructor
public class ConversationContextBuilder {

    private final UserMapper userMapper;
    private final MemoryService memoryService;
    private final ChatMessageMapper messageMapper;

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

        // 4. 组装
        return ConversationContext.builder()
                .sessionId(session.getId())
                .sessionTitle(session.getTitle())
                .userId(userId)
                .nickname(user != null ? user.getNickname() : null)
                .summary(session.getSummary())
                .recentMessages(recent)
                .totalActiveMessageCount(count)
                .build();
    }
}
