package com.niconicode.conversation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_trace")
public class ChatTrace {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long messageId;
    private String stage;       // INTENT, REWRITE, RETRIEVE, TOOL_CALL, GENERATE, SUMMARY
    private String input;
    private String output;
    private Integer durationMs;
    private LocalDateTime createdAt;
}
