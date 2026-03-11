package com.niconicode.errata.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("errata")
public class Errata {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String docType;     // TECH_REPORT, KNOWLEDGE_DOC
    private Long docId;
    private Long userId;
    private String content;
    private String status;      // PENDING, ACCEPTED, REJECTED
    private String adminNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
