package com.niconicode.agent.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatReq {
    private Long sessionId;
    @NotBlank(message = "消息不能为空")
    private String message;
}
