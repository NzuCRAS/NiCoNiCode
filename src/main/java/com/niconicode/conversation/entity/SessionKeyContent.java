package com.niconicode.conversation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("session_key_content")
public class SessionKeyContent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String contentType;  // ENTITY/DECISION/CODE_SNIPPET
    private String content;
    private Long sourceMessageId;
    private LocalDateTime createdAt;
}
