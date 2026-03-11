package com.niconicode.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String password;
    private String nickname;
    private String avatar;
    private String role;      // USER, ADMIN
    private String status;    // ACTIVE, DISABLED
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
