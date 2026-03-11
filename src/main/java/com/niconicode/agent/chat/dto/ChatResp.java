package com.niconicode.agent.chat.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatResp {
    private Long sessionId;
    private String reply;
    private String sessionTitle;
}
