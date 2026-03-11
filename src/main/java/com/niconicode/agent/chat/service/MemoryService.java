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
        // 创建新会话
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(firstMessage.length() > 50 ? firstMessage.substring(0, 50) + "..." : firstMessage);
        sessionMapper.insert(session);
        return session;
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
