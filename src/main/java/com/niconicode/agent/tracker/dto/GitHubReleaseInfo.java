package com.niconicode.agent.tracker.dto;

import lombok.Data;

@Data
public class GitHubReleaseInfo {
    private String tagName;
    private String body;          // release notes
    private String htmlUrl;       // GitHub release 页面链接
    private String publishedAt;   // 发布日期
}
