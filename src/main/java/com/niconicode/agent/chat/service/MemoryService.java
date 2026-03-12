package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.conversation.mapper.ChatMessageMapper;
import com.niconicode.conversation.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    private static final int WINDOW_SIZE = 20;

    public ChatSession getOrCreateSession(Long userId, Long sessionId, String firstMessage) {
        if (sessionId != null) {
            ChatSession session = sessionMapper.selectById(sessionId);
            if (session != null && session.getUserId().equals(userId)) {
                return session;
            }
        }

        // 检查是否存在未发送消息的新会话
        ChatSession existingNewSession = getExistingNewSession(userId);
        if (existingNewSession != null) {
            return existingNewSession;
        }

        // 创建新会话
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle("新对话");  // 初始标题，第一次回答后会更新
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 获取用户现有的未发送消息的新会话
     * 返回有0条消息的最近创建的会话
     */
    public ChatSession getExistingNewSession(Long userId) {
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getCreatedAt)
        );

        for (ChatSession session : sessions) {
            long messageCount = messageMapper.selectCount(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, session.getId())
            );
            if (messageCount == 0) {
                return session;
            }
        }
        return null;
    }

    /**
     * 更新会话标题
     */
    public void updateSessionTitle(Long sessionId, String newTitle) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setTitle(newTitle);
            sessionMapper.updateById(session);
        }
    }

    /**
     * 创建新会话
     */
    public void createNewSession(ChatSession session) {
        sessionMapper.insert(session);
    }

    /**
     * 直接获取会话的所有消息（不验证userId）
     */
    public List<ChatMessage> getSessionMessagesDirectly(Long sessionId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
    }

    public List<ChatMessage> getRecentMessages(Long sessionId) {
        List<ChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + WINDOW_SIZE)
        );
        Collections.reverse(messages);
        return messages;
    }

    public void saveMessage(Long sessionId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTokenCount(content.length() / 4); // 粗略估算
        messageMapper.insert(msg);
    }

    public List<ChatSession> getUserSessions(Long userId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getUpdatedAt)
        );
    }

    public List<ChatMessage> getSessionMessages(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return List.of();
        }
        return messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
    }

    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null && session.getUserId().equals(userId)) {
            messageMapper.delete(
                    new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
            sessionMapper.deleteById(sessionId);
        }
    }
}
