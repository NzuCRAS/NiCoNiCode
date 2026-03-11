package com.niconicode.agent.tracker.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TrackedTechResp {
    private Long id;
    private String name;
    private String category;
    private String githubRepo;
    private String officialUrl;
    private String lastKnownVersion;
    private Integer mentionCount;
    private Boolean isHot;
    private String status;
    private LocalDateTime lastCheckedAt;
}
