package com.niconicode.knowledge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_doc")
public class KnowledgeDoc {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private String sourceType;  // CHAT_SUMMARY, TRACKER_REPORT, MANUAL
    private Long sourceId;
    private String tags;        // 逗号分隔
    private Long categoryId;
    private Integer viewCount;
    private String status;      // ACTIVE, ARCHIVED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
