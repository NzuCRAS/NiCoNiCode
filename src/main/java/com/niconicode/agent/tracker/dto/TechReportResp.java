package com.niconicode.agent.tracker.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TechReportResp {
    private Long id;
    private Long trackedTechId;
    private String techName;
    private String category;
    private String title;
    private String content;
    private String newVersion;
    private String changeSummary;
    private String status;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
