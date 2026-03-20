package com.niconicode.conversation.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_memory")
public class UserMemory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String memoryType;  // TECH_PREFERENCE/EXPERTISE_AREA/INTERACTION_STYLE/TEAM_CONTEXT/FREQUENTLY_ASKED
    private String content;
    private Double confidence;
    private Long sourceSessionId;
    private Integer refreshCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiredAt;
}
