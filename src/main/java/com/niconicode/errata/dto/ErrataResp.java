package com.niconicode.errata.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ErrataResp {
    private Long id;
    private String docType;
    private Long docId;
    private String docTitle;
    private Long userId;
    private String userNickname;
    private String content;
    private String status;
    private String adminNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
