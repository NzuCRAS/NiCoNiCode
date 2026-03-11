package com.niconicode.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    // 简单的内存验证码存储: email -> {code, expireTime}
    private final Map<String, CodeEntry> codeStore = new ConcurrentHashMap<>();

    public void sendVerificationCode(String toEmail) {
        String code = String.format("%06d", new Random().nextInt(1000000));
        codeStore.put(toEmail, new CodeEntry(code, System.currentTimeMillis() + 5 * 60 * 1000));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(toEmail);
        message.setSubject("NiCoNiCode - 邮箱验证码");
        message.setText("您的验证码是: " + code + "\n\n验证码5分钟内有效。\n如果这不是您的操作，请忽略此邮件。");

        try {
            mailSender.send(message);
            log.info("Verification code sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send email to {}", toEmail, e);
            throw new RuntimeException("邮件发送失败，请稍后重试");
        }
    }

    public boolean verifyCode(String email, String code) {
        CodeEntry entry = codeStore.get(email);
        if (entry == null) return false;
        if (System.currentTimeMillis() > entry.expireTime) {
            codeStore.remove(email);
            return false;
        }
        if (entry.code.equals(code)) {
            codeStore.remove(email);
            return true;
        }
        return false;
    }

    private record CodeEntry(String code, long expireTime) {}
}
