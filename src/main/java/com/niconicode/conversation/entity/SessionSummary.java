package com.niconicode.conversation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("session_summary")
public class SessionSummary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer version;
    private String content;
    private String triggerType;
    private LocalDateTime createdAt;
}
