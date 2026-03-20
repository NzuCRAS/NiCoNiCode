package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import com.niconicode.conversation.entity.SessionKeyContent;
import com.niconicode.conversation.entity.SessionSummary;
import com.niconicode.conversation.mapper.ChatMessageMapper;
import com.niconicode.conversation.mapper.ChatSessionMapper;
import com.niconicode.conversation.mapper.SessionKeyContentMapper;
import com.niconicode.conversation.mapper.SessionSummaryMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.niconicode.common.util.SafeTemplates;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final SessionSummaryMapper summaryMapper;
    private final SessionKeyContentMapper keyContentMapper;
    private final ChatLanguageModel chatModel;
    private final TraceLogger traceLogger;

    private static final int WINDOW_SIZE = 20;
    private static final int SUMMARY_TRIGGER_THRESHOLD = 30;

    public int getWindowSize() {
        return WINDOW_SIZE;
    }

    public int getSummaryTriggerThreshold() {
        return SUMMARY_TRIGGER_THRESHOLD;
    }

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
                            .isNull(ChatMessage::getDeletedAt)
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
                        .isNull(ChatMessage::getDeletedAt)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
    }

    public List<ChatMessage> getRecentMessages(Long sessionId) {
        List<ChatMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .isNull(ChatMessage::getDeletedAt)
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + WINDOW_SIZE)
        );
        Collections.reverse(messages);
        return messages;
    }

    public void saveMessage(Long sessionId, String role, String content) {
        saveMessage(sessionId, role, content, null);
    }

    public void saveMessage(Long sessionId, String role, String content, String metadata) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTokenCount(content.length() / 4);
        msg.setMetadata(metadata);
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
                        .isNull(ChatMessage::getDeletedAt)
                        .orderByAsc(ChatMessage::getCreatedAt)
        );
    }

    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null && session.getUserId().equals(userId)) {
            // 会话级别硬删除（用户主动删除整个会话）
            messageMapper.delete(
                    new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
            summaryMapper.delete(
                    new LambdaQueryWrapper<SessionSummary>().eq(SessionSummary::getSessionId, sessionId));
            keyContentMapper.delete(
                    new LambdaQueryWrapper<SessionKeyContent>().eq(SessionKeyContent::getSessionId, sessionId));
            sessionMapper.deleteById(sessionId);
        }
    }

    /**
     * 软删除单条消息 + 异步重算摘要
     */
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) throw new BusinessException(404, "消息不存在");

        ChatSession session = sessionMapper.selectById(message.getSessionId());
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作");
        }

        // 软删除
        message.setDeletedAt(LocalDateTime.now());
        messageMapper.updateById(message);

        // 异步重算摘要
        Long sessionId = message.getSessionId();
        CompletableFuture.runAsync(() -> {
            try {
                recalculateSummaryIfNeeded(sessionId);
            } catch (Exception e) {
                log.warn("Async summary recalculation failed for session {}", sessionId, e);
            }
        });
    }

    /**
     * 软删除某条消息之后的所有消息
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

        messageMapper.update(null,
                new LambdaUpdateWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .gt(ChatMessage::getId, messageId)
                        .isNull(ChatMessage::getDeletedAt)
                        .set(ChatMessage::getDeletedAt, LocalDateTime.now())
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
     * 然后软删除已摘要的旧消息以节省上下文空间。
     */
    public void compressMemoryIfNeeded(Long sessionId) {
        compressMemoryIfNeeded(sessionId, null);
    }

    /**
     * 自动摘要压缩（支持 traceId 观测）
     */
    public void compressMemoryIfNeeded(Long sessionId, TraceLogger.TraceContext traceCtx) {
        long totalCount = messageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .isNull(ChatMessage::getDeletedAt));

        if (traceCtx != null) {
            traceLogger.trace(traceCtx, "MEMORY_COMPRESS_CHECK",
                    "totalCount=" + totalCount + ", windowSize=" + WINDOW_SIZE
                            + ", triggerThreshold=" + SUMMARY_TRIGGER_THRESHOLD);
        }

        if (totalCount <= SUMMARY_TRIGGER_THRESHOLD) return;

        // 获取窗口外的旧消息（总数 - WINDOW_SIZE 条最早的消息）
        long oldCount = totalCount - WINDOW_SIZE;
        List<ChatMessage> oldMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .isNull(ChatMessage::getDeletedAt)
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

        if (traceCtx != null) {
        traceLogger.trace(traceCtx, "MEMORY_COMPRESS_CONTEXT",
            "oldMessages=" + oldMessages.size() + ", hasExistingSummary="
                + (existingSummary != null && !existingSummary.isBlank()));
        }

            String prompt;
            if (existingSummary != null && !existingSummary.isBlank()) {
        prompt = """
            请将以下现有摘要与新的对话内容合并，生成一份简洁的综合摘要。
            保留关键信息：用户偏好、讨论的技术话题、重要结论、待办事项。
            摘要限制在 500 字以内。

            现有摘要：
            """ + SafeTemplates.s(existingSummary)
            + """

            新对话内容：
            """ + SafeTemplates.s(dialogText);
            } else {
        prompt = """
            请简洁总结以下对话的关键信息，保留：
            1. 用户关注的技术话题和偏好
            2. 讨论中的重要结论和建议
            3. 用户提到的具体需求或待办事项
            摘要限制在 500 字以内。

            对话内容：
            """ + SafeTemplates.s(dialogText);
            }

            String summary = chatModel.chat(prompt);

            // 版本化保存摘要
            saveSummary(sessionId, summary, "AUTO_COMPRESS");

            if (traceCtx != null) {
                traceLogger.trace(traceCtx, "MEMORY_COMPRESS_SUMMARY",
                        "summaryLength=" + (summary != null ? summary.length() : 0));
            }

            // 软删除已摘要的旧消息
            List<Long> oldIds = oldMessages.stream().map(ChatMessage::getId).toList();
            messageMapper.update(null,
                    new LambdaUpdateWrapper<ChatMessage>()
                            .in(ChatMessage::getId, oldIds)
                            .set(ChatMessage::getDeletedAt, LocalDateTime.now())
            );

            log.info("Compressed memory for session {}: summarized {} messages, {} remaining",
                    sessionId, oldMessages.size(), WINDOW_SIZE);
        } catch (Exception e) {
            log.warn("Failed to compress memory for session {}", sessionId, e);

            if (traceCtx != null) {
                traceLogger.traceError(traceCtx, "MEMORY_COMPRESS", e);
            }
        }
    }

    /**
     * 版本化保存摘要（追加写入 + 更新缓存）
     */
    private void saveSummary(Long sessionId, String content, String triggerType) {
        // 查当前最大版本号
        SessionSummary latest = summaryMapper.selectOne(
                new LambdaQueryWrapper<SessionSummary>()
                        .eq(SessionSummary::getSessionId, sessionId)
                        .orderByDesc(SessionSummary::getVersion)
                        .last("LIMIT 1"));
        int newVersion = (latest != null ? latest.getVersion() : 0) + 1;

        // INSERT 新版本（追加写入，无行锁竞争）
        SessionSummary summary = new SessionSummary();
        summary.setSessionId(sessionId);
        summary.setVersion(newVersion);
        summary.setContent(content);
        summary.setTriggerType(triggerType);
        summaryMapper.insert(summary);

        // 同步更新 chat_session.summary 缓存
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            session.setSummary(content);
            sessionMapper.updateById(session);
        }
    }

    /**
     * 消息删除后重新计算摘要
     */
    private void recalculateSummaryIfNeeded(Long sessionId) {
        List<ChatMessage> activeMessages = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .isNull(ChatMessage::getDeletedAt)
                        .orderByAsc(ChatMessage::getCreatedAt));

        if (activeMessages.size() < 5) return; // 消息太少不需要摘要

        String dialogText = activeMessages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        if (dialogText.length() > 4000) {
            dialogText = dialogText.substring(0, 4000);
        }

    String prompt = """
        请简洁总结以下对话的关键信息，保留：
        1. 用户关注的技术话题和偏好
        2. 讨论中的重要结论和建议
        3. 用户提到的具体需求或待办事项
        摘要限制在 500 字以内。

        对话内容：
        """ + SafeTemplates.s(dialogText);

        String newSummary = chatModel.chat(prompt);
        saveSummary(sessionId, newSummary, "MSG_DELETE");
    }
}
