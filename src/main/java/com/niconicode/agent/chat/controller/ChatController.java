package com.niconicode.agent.chat.controller;

import com.niconicode.agent.chat.dto.ChatReq;
import com.niconicode.agent.chat.dto.ChatResp;
import com.niconicode.agent.chat.service.ChatService;
import com.niconicode.agent.chat.service.MemoryService;
import com.niconicode.common.result.R;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final MemoryService memoryService;

    @PostMapping("/send")
    public R<ChatResp> send(@Valid @RequestBody ChatReq req, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return R.ok(chatService.processMessage(userId, req));
    }

    @PostMapping(value = "/send/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendStream(@Valid @RequestBody ChatReq req, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return chatService.processMessageStream(userId, req);
    }

    @GetMapping("/sessions")
    public R<List<ChatSession>> sessions(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return R.ok(memoryService.getUserSessions(userId));
    }

    @GetMapping("/sessions/{id}/messages")
    public R<List<ChatMessage>> messages(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return R.ok(memoryService.getSessionMessages(id, userId));
    }

    @PostMapping("/sessions")
    public R<ChatSession> createSession(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        // 检查是否已经有未发送消息的会话，如果有则返回它
        ChatSession session = memoryService.getExistingNewSession(userId);
        if (session != null) {
            return R.ok(session);
        }
        // 否则创建新会话
        session = new ChatSession();
        session.setUserId(userId);
        session.setTitle("新对话");
        memoryService.createNewSession(session);
        return R.ok(session);
    }

    @DeleteMapping("/sessions/{id}")
    public R<Void> deleteSession(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        memoryService.deleteSession(id, userId);
        return R.ok();
    }
}
