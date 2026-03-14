package com.niconicode.auth.controller;

import com.niconicode.auth.dto.AuthResp;
import com.niconicode.auth.dto.LoginReq;
import com.niconicode.auth.dto.RegisterReq;
import com.niconicode.auth.dto.ResetPasswordReq;
import com.niconicode.auth.entity.User;
import com.niconicode.auth.service.AuthService;
import com.niconicode.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-code")
    public R<Void> sendCode(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return R.fail(400, "邮箱不能为空");
        }
        authService.sendCode(email);
        return R.ok();
    }

    @PostMapping("/register")
    public R<AuthResp> register(@Valid @RequestBody RegisterReq req) {
        return R.ok(authService.register(req));
    }

    @PostMapping("/login")
    public R<AuthResp> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req));
    }

    @PostMapping("/reset-password")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordReq req) {
        authService.resetPassword(req.getEmail(), req.getCode(), req.getNewPassword());
        return R.ok();
    }

    @GetMapping("/me")
    public R<User> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return R.ok(authService.getCurrentUser(userId));
    }
}
