package com.niconicode.admin.dto;

import lombok.Data;

@Data
public class TechReportUpdateReq {
    private String title;
    private String content;
    private Long categoryId;
    private String status;  // DRAFT, PUBLISHED
}
