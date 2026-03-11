package com.niconicode.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResp {
    private String token;
    private Long userId;
    private String email;
    private String nickname;
    private String role;
}
