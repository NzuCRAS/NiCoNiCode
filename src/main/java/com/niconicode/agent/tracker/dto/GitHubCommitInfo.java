package com.niconicode.agent.tracker.dto;

import lombok.Data;

@Data
public class GitHubCommitInfo {
    private String sha;
    private String message;
    private String authorName;
    private String date;
    private String htmlUrl;
}
