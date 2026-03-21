package com.niconicode.knowledge.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KnowledgeDocResp {
    private Long id;
    private String title;
    private String content;
    private String sourceType;
    private String tags;
    private Integer viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
