package com.niconicode.admin.dto;

import lombok.Data;

@Data
public class TechReportUpdateReq {
    private String title;
    private String content;
    private Long categoryId;
    private Long trackedTechId;
    private Integer techIndex;
    private String status;  // DRAFT, PUBLISHED
}
