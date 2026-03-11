package com.niconicode.conversation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String role;     // USER, ASSISTANT, SYSTEM
    private String content;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}
