package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.conversation.mapper.ChatMessageMapper;
import com.niconicode.conversation.mapper.ChatSessionMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    private static final int WINDOW_SIZE = 20;
    private static final int SUMMARY_TRIGGER_THRESHOLD = 30;

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
        session.setTitle("新对话");
        sessionMapper.insert(session);
        return session;
    }

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

    public void updateSessionTitle(Long sessionId, String newTitle) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setTitle(newTitle);
            sessionMapper.updateById(session);
        }
    }

    public void createNewSession(ChatSession session) {
        sessionMapper.insert(session);
    }

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
        msg.setTokenCount(content.length() / 4);
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

    /**
     * 删除单条消息（验证权限）
     */
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) throw new BusinessException(404, "消息不存在");

        ChatSession session = sessionMapper.selectById(message.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作");
        }

        messageMapper.deleteById(messageId);
    }

    /**
     * 删除某条消息之后的所有消息
     */
    public void deleteMessagesAfter(Long sessionId, Long messageId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作");
        }

        ChatMessage targetMessage = messageMapper.selectById(messageId);
        if (targetMessage == null || !targetMessage.getSessionId().equals(sessionId)) {
            throw new BusinessException(404, "消息不存在");
        }

        messageMapper.delete(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .gt(ChatMessage::getId, messageId)
        );
    }

    /**
     * 更新消息内容
     */
    public void updateMessage(Long messageId, String newContent, Long userId) {
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) throw new BusinessException(404, "消息不存在");

        ChatSession session = sessionMapper.selectById(message.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作");
        }

        message.setContent(newContent);
        message.setTokenCount(newContent.length() / 4);
        messageMapper.updateById(message);
    }

    /**
     * 当消息数超过阈值时，对窗口外的旧消息生成 AI 摘要并存入 session.summary，
     * 然后删除已摘要的旧消息以节省存储和上下文空间。
     */
    public void compressMemoryIfNeeded(Long sessionId, ChatLanguageModel chatModel) {
        long totalCount = messageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));

        if (totalCount <= SUMMARY_TRIGGER_THRESHOLD) return;

        // 获取窗口外的旧消息（总数 - WINDOW_SIZE 条最早的消息）
        long oldCount = totalCount - WINDOW_SIZE;
        List<ChatMessage> oldMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + oldCount));

        if (oldMessages.isEmpty()) return;

        // 构造对话文本
        String dialogText = oldMessages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        // 截断过长的文本
        if (dialogText.length() > 4000) {
            dialogText = dialogText.substring(0, 4000);
        }

        try {
            // 获取现有摘要（增量合并）
            ChatSession session = sessionMapper.selectById(sessionId);
            String existingSummary = session.getSummary();

            String prompt;
            if (existingSummary != null && !existingSummary.isBlank()) {
                prompt = """
                        请将以下现有摘要与新的对话内容合并，生成一份简洁的综合摘要。
                        保留关键信息：用户偏好、讨论的技术话题、重要结论、待办事项。
                        摘要限制在 500 字以内。

                        现有摘要：
                        %s

                        新对话内容：
                        %s
                        """.formatted(existingSummary, dialogText);
            } else {
                prompt = """
                        请简洁总结以下对话的关键信息，保留：
                        1. 用户关注的技术话题和偏好
                        2. 讨论中的重要结论和建议
                        3. 用户提到的具体需求或待办事项
                        摘要限制在 500 字以内。

                        对话内容：
                        %s
                        """.formatted(dialogText);
            }

            String summary = chatModel.chat(prompt);

            // 更新 session summary
            session.setSummary(summary);
            sessionMapper.updateById(session);

            // 删除已摘要的旧消息
            List<Long> oldIds = oldMessages.stream().map(ChatMessage::getId).toList();
            messageMapper.deleteBatchIds(oldIds);

            log.info("Compressed memory for session {}: summarized {} messages, {} remaining",
                    sessionId, oldMessages.size(), WINDOW_SIZE);
        } catch (Exception e) {
            log.warn("Failed to compress memory for session {}", sessionId, e);
        }
    }
}
