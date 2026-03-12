package com.niconicode.agent.tracker.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tech_report")
public class TechReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trackedTechId;
    private String title;
    private String content;
    private String newVersion;
    private String changeSummary;
    private String sourceUrls;  // JSON array
    private Long categoryId;
    private String status;      // DRAFT, PUBLISHED
    private Integer techIndex;  // 技术指数 (0-1000)
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
