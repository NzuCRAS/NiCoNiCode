package com.niconicode.agent.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubTask {

    private String id;                    // "t1", "t2", ...
    private String query;                 // 该子任务的查询文本

    @Builder.Default
    private SubTaskType type = SubTaskType.QUESTION;

    @Builder.Default
    private IntentClassification.Intent intent = IntentClassification.Intent.GENERAL_CHAT;

    @Builder.Default
    private IntentClassification.SubIntent subIntent = IntentClassification.SubIntent.UNKNOWN;

    @Builder.Default
    private Map<String, String> slots = new HashMap<>();

    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();  // 前驱节点 ID 列表（空=根节点）

    // 执行后填充
    private String ragContext;

    @Builder.Default
    private SubTaskStatus status = SubTaskStatus.PENDING;

    public enum SubTaskType {
        QUESTION, TASK, COMPARISON_AXIS
    }

    public enum SubTaskStatus {
        PENDING, RUNNING, DONE, FAILED
    }
}
