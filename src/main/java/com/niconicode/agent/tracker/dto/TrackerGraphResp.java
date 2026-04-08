package com.niconicode.agent.tracker.dto;

import com.niconicode.agent.tracker.entity.TechReport;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页知识图谱响应结构：类型 -> 技术 -> 报道(Top N)
 */
public class TrackerGraphResp {

    @Data
    public static class CategoryNode {
        private String category;
        private List<TechNode> techs = new ArrayList<>();
    }

    @Data
    public static class TechNode {
        private Long techId;
        private String techName;
        private String trackingMode;
        private String lastKnownVersion;
        private List<TechReport> reports = new ArrayList<>();
    }
}
