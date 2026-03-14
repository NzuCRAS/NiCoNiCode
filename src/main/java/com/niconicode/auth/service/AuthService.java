package com.niconicode.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.niconicode.auth.dto.AuthResp;
import com.niconicode.auth.dto.LoginReq;
import com.niconicode.auth.dto.RegisterReq;
import com.niconicode.auth.entity.User;
import com.niconicode.auth.mapper.UserMapper;
import com.niconicode.common.exception.BusinessException;
import com.niconicode.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final EmailService emailService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public void sendCode(String email) {
        emailService.sendVerificationCode(email);
    }

    public AuthResp register(RegisterReq req) {
        // 验证码校验
        if (!emailService.verifyCode(req.getEmail(), req.getCode())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }

        // 邮箱唯一性校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, req.getEmail()));
        if (count > 0) {
            throw new BusinessException(400, "该邮箱已注册");
        }

        // 创建用户
        User user = new User();
        user.setEmail(req.getEmail());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() != null ? req.getNickname() : "用户" + req.getEmail().split("@")[0]);
        user.setRole("USER");
        user.setStatus("ACTIVE");
        userMapper.insert(user);

        // 生成 JWT
        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return AuthResp.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole())
                .build();
    }

    public AuthResp login(LoginReq req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, req.getEmail()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "邮箱或密码错误");
        }
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        return AuthResp.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .role(user.getRole())
                .build();
    }

    public User getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        user.setPassword(null);
        return user;
    }

    public void resetPassword(String email, String code, String newPassword) {
        if (!emailService.verifyCode(email, code)) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (user == null) {
            throw new BusinessException(404, "该邮箱未注册");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }
}
