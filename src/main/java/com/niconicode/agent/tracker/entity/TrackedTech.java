package com.niconicode.agent.tracker.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tracked_tech")
public class TrackedTech {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String category;
    private String githubRepo;
    private String officialUrl;
    private String rssUrl;
    private String sitemapUrl;
    private String lastKnownVersion;
    private String trackingMode;        // RELEASE, TAG, COMMIT
    private String lastKnownCommitSha;
    private LocalDateTime lastCheckedAt;
    private Integer mentionCount;
    private Boolean isHot;
    private String status;  // ACTIVE, PAUSED
    private LocalDateTime createdAt;
}
