package com.niconicode.agent.chat.controller;

import com.niconicode.agent.chat.dto.ChatReq;
import com.niconicode.agent.chat.dto.ChatResp;
import com.niconicode.agent.chat.service.ChatService;
import com.niconicode.agent.chat.service.MemoryService;
import com.niconicode.common.result.R;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.entity.ChatSession;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<SseEmitter> sendStream(@Valid @RequestBody ChatReq req, Authentication auth,
                                                  HttpServletResponse servletResponse) {
        // 禁用 Tomcat 响应缓冲区，确保每次 SseEmitter.send() 的数据立即刷到客户端
        // 默认 8KB 缓冲区会把小的 SSE 事件攒在一起，导致前端看不到流式输出
        servletResponse.setBufferSize(0);

        Long userId = (Long) auth.getPrincipal();
        SseEmitter emitter = chatService.processMessageStream(userId, req);

        HttpHeaders headers = new HttpHeaders();
        // 防止反向代理/网关缓冲 SSE，导致”只显示前几个字，最后一次性出现”
        headers.add("X-Accel-Buffering", "no");
        headers.add("Cache-Control", "no-cache, no-transform");
        headers.add("Connection", "keep-alive");
        return ResponseEntity.ok().headers(headers).body(emitter);
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
        ChatSession session = memoryService.getExistingNewSession(userId);
        if (session != null) {
            return R.ok(session);
        }
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

    @DeleteMapping("/messages/{id}")
    public R<Void> deleteMessage(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        memoryService.deleteMessage(id, userId);
        return R.ok();
    }

    @DeleteMapping("/sessions/{sessionId}/messages/after/{messageId}")
    public R<Void> deleteMessagesAfter(@PathVariable Long sessionId, @PathVariable Long messageId, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        memoryService.deleteMessagesAfter(sessionId, messageId, userId);
        return R.ok();
    }

    @PutMapping("/messages/{id}")
    public R<Void> updateMessage(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        memoryService.updateMessage(id, body.get("content"), userId);
        return R.ok();
    }

    @PostMapping("/stream/cancel")
    public R<Void> cancelStream(@RequestBody Map<String, Long> body, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        Long sessionId = body.get("sessionId");
        if (sessionId != null) {
            chatService.cancelStream(sessionId, userId);
        }
        return R.ok();
    }
}
