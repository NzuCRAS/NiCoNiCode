package com.niconicode.agent.tracker.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("hot_topic")
public class HotTopic {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String keyword;
    private Integer mentionCount;
    private LocalDateTime lastMentionedAt;
    private Boolean promotedToTracked;
    private LocalDateTime createdAt;
}
