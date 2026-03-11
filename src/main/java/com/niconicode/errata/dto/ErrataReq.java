package com.niconicode.errata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ErrataReq {
    @NotBlank(message = "文档类型不能为空")
    private String docType;     // TECH_REPORT, KNOWLEDGE_DOC
    @NotNull(message = "文档ID不能为空")
    private Long docId;
    @NotBlank(message = "勘误内容不能为空")
    private String content;
}
