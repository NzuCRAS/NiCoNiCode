package com.niconicode.admin.dto;

import lombok.Data;

@Data
public class UserUpdateReq {
    private String role;    // USER, ADMIN
    private String status;  // ACTIVE, DISABLED
}
