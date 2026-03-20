package com.niconicode.agent.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.chat.service.TraceLogger.TraceContext;
import com.niconicode.conversation.entity.UserMemory;
import com.niconicode.conversation.entity.ChatMessage;
import com.niconicode.conversation.mapper.UserMemoryMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserMemoryService {

    private final UserMemoryMapper userMemoryMapper;
    private final ChatLanguageModel fastChatModel;
    private final TraceLogger traceLogger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserMemoryService(UserMemoryMapper userMemoryMapper,
                             @Qualifier("fastChatModel") ChatLanguageModel fastChatModel,
                             TraceLogger traceLogger) {
        this.userMemoryMapper = userMemoryMapper;
        this.fastChatModel = fastChatModel;
        this.traceLogger = traceLogger;
    }

    /**
     * 加载用户活跃记忆（expired_at IS NULL），30天未更新的 confidence 衰减
     */
    public List<UserMemory> getUserMemories(Long userId) {
        List<UserMemory> memories = userMemoryMapper.selectList(
                new LambdaQueryWrapper<UserMemory>()
                        .eq(UserMemory::getUserId, userId)
                        .isNull(UserMemory::getExpiredAt)
                        .orderByDesc(UserMemory::getConfidence)
                        .orderByDesc(UserMemory::getUpdatedAt));

        LocalDateTime now = LocalDateTime.now();
        for (UserMemory m : memories) {
            if (m.getUpdatedAt() != null) {
                long daysSinceUpdate = ChronoUnit.DAYS.between(m.getUpdatedAt(), now);
                if (daysSinceUpdate > 30) {
                    // 内存中衰减，不持久化
                    m.setConfidence(m.getConfidence() * 0.5);
                }
            }
        }
        return memories;
    }

    /**
     * 从对话中提取用户画像并脱敏保存
     */
    public void extractAndSaveUserProfile(Long userId, Long sessionId,
                                          List<ChatMessage> recentMessages, TraceContext traceCtx) {
        if (recentMessages == null || recentMessages.size() < 3) return;

        String dialogText = recentMessages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        if (dialogText.length() > 3000) {
            dialogText = dialogText.substring(0, 3000);
        }

        String prompt = """
                从以下对话中提取用户画像信息，严格遵守脱敏规则。仅返回JSON。

                脱敏规则（必须严格遵守）：
                - 禁止保留：公司名、部门名、团队名、个人姓名、具体地址
                - 团队信息仅保留：规模(如"小型团队约5人")、工作重心(如"后端微服务")、技术缺口(如"缺少前端经验")
                - 不确定能否脱敏的信息，直接省略

                提取类别：
                1. TECH_PREFERENCE: 偏好的技术/框架/语言/工具
                2. EXPERTISE_AREA: 擅长领域（从讨论深度判断）
                3. INTERACTION_STYLE: 偏好代码示例/详细解释/简洁回答
                4. TEAM_CONTEXT: 脱敏后的团队背景
                5. FREQUENTLY_ASKED: 反复关注的话题

                返回格式: {"memories": [{"type": "TECH_PREFERENCE", "content": "偏好 Spring Boot 和 Vue 3", "confidence": 0.8}]}
                如果无法提取有效信息，返回: {"memories": []}

                对话内容：
                """ + dialogText;

        try {
            long start = System.currentTimeMillis();
            String response = fastChatModel.chat(prompt);
            int duration = (int) (System.currentTimeMillis() - start);

            // 解析 JSON
            String json = extractJson(response);
            JsonNode root = objectMapper.readTree(json);
            JsonNode memoriesNode = root.get("memories");

            if (memoriesNode == null || !memoriesNode.isArray()) return;

            int saved = 0;
            for (JsonNode item : memoriesNode) {
                String type = item.has("type") ? item.get("type").asText() : null;
                String content = item.has("content") ? item.get("content").asText() : null;
                double confidence = item.has("confidence") ? item.get("confidence").asDouble(0.5) : 0.5;

                if (type == null || content == null || content.isBlank()) continue;
                if (!isValidMemoryType(type)) continue;

                refreshMemory(userId, type, content, confidence, sessionId);
                saved++;
            }

            // 合并过多的同类记忆
            for (String type : List.of("TECH_PREFERENCE", "EXPERTISE_AREA", "INTERACTION_STYLE",
                    "TEAM_CONTEXT", "FREQUENTLY_ASKED")) {
                consolidateMemories(userId, type);
            }

            if (traceCtx != null) {
                traceLogger.trace(traceCtx, "USER_PROFILE_EXTRACT",
                        "saved=" + saved + ", duration=" + duration + "ms");
            }
        } catch (Exception e) {
            log.warn("User profile extraction failed for user {}: {}", userId, e.getMessage());
            if (traceCtx != null) {
                traceLogger.traceError(traceCtx, "USER_PROFILE_EXTRACT", e);
            }
        }
    }

    /**
     * 已有类似记忆时刷新，否则新建
     */
    void refreshMemory(Long userId, String memoryType, String content, double confidence, Long sessionId) {
        List<UserMemory> existing = userMemoryMapper.selectList(
                new LambdaQueryWrapper<UserMemory>()
                        .eq(UserMemory::getUserId, userId)
                        .eq(UserMemory::getMemoryType, memoryType)
                        .isNull(UserMemory::getExpiredAt));

        // 简单关键词重叠检测（Jaccard >= 0.3 视为相似）
        for (UserMemory mem : existing) {
            if (jaccardSimilarity(mem.getContent(), content) >= 0.3) {
                mem.setRefreshCount(mem.getRefreshCount() + 1);
                mem.setConfidence(Math.min(1.0, mem.getConfidence() + 0.05));
                mem.setUpdatedAt(LocalDateTime.now());
                userMemoryMapper.updateById(mem);
                return;
            }
        }

        // 新建条目
        UserMemory newMem = new UserMemory();
        newMem.setUserId(userId);
        newMem.setMemoryType(memoryType);
        newMem.setContent(content);
        newMem.setConfidence(confidence);
        newMem.setSourceSessionId(sessionId);
        newMem.setRefreshCount(1);
        userMemoryMapper.insert(newMem);
    }

    /**
     * 同类超过5条时合并为 ≤3 条
     */
    void consolidateMemories(Long userId, String memoryType) {
        List<UserMemory> memories = userMemoryMapper.selectList(
                new LambdaQueryWrapper<UserMemory>()
                        .eq(UserMemory::getUserId, userId)
                        .eq(UserMemory::getMemoryType, memoryType)
                        .isNull(UserMemory::getExpiredAt)
                        .orderByDesc(UserMemory::getConfidence));

        if (memories.size() <= 5) return;

        String allContent = memories.stream()
                .map(m -> "- " + m.getContent() + " (confidence=" + m.getConfidence() + ")")
                .collect(Collectors.joining("\n"));

        String prompt = """
                将以下同类用户记忆合并为不超过3条，保留最重要的信息，去除重复。
                严格遵守脱敏规则：不保留公司名、部门名、个人姓名。
                返回JSON: {"merged": [{"content": "合并后内容", "confidence": 0.8}]}

                当前记忆：
                """ + allContent;

        try {
            String response = fastChatModel.chat(prompt);
            String json = extractJson(response);
            JsonNode root = objectMapper.readTree(json);
            JsonNode merged = root.get("merged");

            if (merged == null || !merged.isArray() || merged.isEmpty()) return;

            // 删除旧条目
            for (UserMemory m : memories) {
                userMemoryMapper.deleteById(m.getId());
            }

            // 插入合并后的条目
            for (JsonNode item : merged) {
                UserMemory newMem = new UserMemory();
                newMem.setUserId(userId);
                newMem.setMemoryType(memoryType);
                newMem.setContent(item.get("content").asText());
                newMem.setConfidence(item.has("confidence") ? item.get("confidence").asDouble(0.8) : 0.8);
                newMem.setRefreshCount(memories.stream().mapToInt(UserMemory::getRefreshCount).sum() / merged.size());
                userMemoryMapper.insert(newMem);
            }

            log.info("Consolidated {} {} memories into {} for user {}",
                    memories.size(), memoryType, merged.size(), userId);
        } catch (Exception e) {
            log.warn("Memory consolidation failed for user {} type {}: {}",
                    userId, memoryType, e.getMessage());
        }
    }

    /**
     * 格式化用户记忆为注入文本
     */
    public static String formatForContext(List<UserMemory> memories) {
        if (memories == null || memories.isEmpty()) return "";

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (UserMemory m : memories) {
            grouped.computeIfAbsent(m.getMemoryType(), k -> new ArrayList<>())
                    .add(m.getContent());
        }

        StringBuilder sb = new StringBuilder("--- 用户画像 ---\n");
        Map<String, String> labels = Map.of(
                "TECH_PREFERENCE", "技术偏好",
                "EXPERTISE_AREA", "擅长领域",
                "INTERACTION_STYLE", "交互风格",
                "TEAM_CONTEXT", "团队背景",
                "FREQUENTLY_ASKED", "常关注"
        );

        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String label = labels.getOrDefault(entry.getKey(), entry.getKey());
            sb.append(label).append(": ").append(String.join(", ", entry.getValue())).append("\n");
        }
        sb.append("--- 用户画像结束 ---");
        return sb.toString();
    }

    // ---- 工具方法 ----

    private static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.split("[\\s,，;；、。.!！?？]+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.split("[\\s,，;；、。.!！?？]+")));
        setA.removeIf(String::isBlank);
        setB.removeIf(String::isBlank);
        if (setA.isEmpty() || setB.isEmpty()) return 0;

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return (double) intersection.size() / union.size();
    }

    private static boolean isValidMemoryType(String type) {
        return Set.of("TECH_PREFERENCE", "EXPERTISE_AREA", "INTERACTION_STYLE",
                "TEAM_CONTEXT", "FREQUENTLY_ASKED").contains(type);
    }

    private static String extractJson(String response) {
        if (response == null) return "{}";
        // 尝试从 markdown 代码块中提取
        int start = response.indexOf("```json");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        start = response.indexOf("```");
        if (start >= 0) {
            start = response.indexOf('\n', start) + 1;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        // 尝试直接查找 JSON 对象
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }
        return response.trim();
    }
}
