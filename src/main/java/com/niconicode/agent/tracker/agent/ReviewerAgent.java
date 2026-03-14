package com.niconicode.agent.tracker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.niconicode.agent.tracker.entity.TrackedTech;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审核 Agent
 * 审核报道质量 + 技术评分 + 发布决策
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent {

    private final ChatLanguageModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class ReviewResult {
        private boolean approved = true;
        private String rejectionReason;
        private String revisedContent;
        private int techIndex;
        private String qualityNotes;
    }

    /**
     * 审核报道：内容审核 + 质量评分 + 发布决策
     */
    public ReviewResult review(TrackedTech tech, String reportContent,
                                SearchAgent.SearchResult searchResult) {
        ReviewResult result = new ReviewResult();

        // Step 1: 内容审核
        String contentReview = reviewContent(reportContent);
        result.setRevisedContent(contentReview);

        // Step 2: 技术指数评分
        int techIndex = scoreTechIndex(tech, reportContent, searchResult);
        result.setTechIndex(techIndex);

        // Step 3: 低质量处理
        if (searchResult.getDataSufficiency() == SearchAgent.DataSufficiency.LOW) {
            result.setTechIndex(Math.min(techIndex, 200));
            result.setQualityNotes("数据来源有限，技术指数已压低");
        }

        // Step 4: 极低质量拒绝
        if (techIndex < 50 && searchResult.getDataSufficiency() == SearchAgent.DataSufficiency.LOW) {
            result.setApproved(false);
            result.setRejectionReason("数据不足且技术指数过低");
        }

        return result;
    }

    /**
     * 内容审核：检查敏感信息、虚假信息、不当用语
     * 返回修订后的内容（或原内容如果无需修改）
     */
    private String reviewContent(String reportContent) {
        String prompt = """
                你是一位技术报道审核编辑。请审核以下报道内容，并返回修订后的版本。

                审核要点：
                1. 删除任何新闻腔调（"业内专家指出"、"据了解"、"值得一提的是"等）
                2. 删除虚构的信息（如果内容中有无法从原始数据推断的断言）
                3. 确保技术术语使用准确
                4. 确保 Markdown 格式正确

                如果内容质量良好无需修改，请原样返回。
                直接返回修订后的 Markdown 内容，不要添加额外说明。

                待审核内容:
                %s
                """.formatted(reportContent);

        try {
            String revised = chatModel.chat(prompt).trim();
            // 清理可能的 markdown 包裹
            if (revised.startsWith("```")) {
                int firstNewline = revised.indexOf('\n');
                if (firstNewline > 0 && revised.endsWith("```")) {
                    revised = revised.substring(firstNewline + 1, revised.length() - 3).trim();
                }
            }
            return revised;
        } catch (Exception e) {
            log.warn("Content review failed, using original", e);
            return reportContent;
        }
    }

    /**
     * 精细化技术指数评分 (0-1000)
     * 技术指数 = 技术影响力(40%) + 更新质量(30%) + 信息完整度(30%)
     */
    private int scoreTechIndex(TrackedTech tech, String reportContent,
                                SearchAgent.SearchResult searchResult) {
        String dataSufficiency = searchResult.getDataSufficiency().name();
        int sourceCount = searchResult.getSourceUrls().size();
        boolean hasReleaseNotes = searchResult.getReleaseInfo() != null
                && searchResult.getReleaseInfo().getBody() != null
                && searchResult.getReleaseInfo().getBody().length() > 50;
        int commitCount = searchResult.getRecentCommits().size();

        String prompt = """
                请为以下技术更新生成一个 0-1000 的综合技术指数。返回严格 JSON 格式:
                {"techInfluence": 0-400, "updateQuality": 0-300, "infoCompleteness": 0-300, "total": 0-1000}

                评分维度:

                1. 技术影响力 (0-400):
                   - 项目知名度、Star 数、生态规模
                   - 用户基数和社区活跃度
                   参考: Spring/React/Vue=350+, 知名工具=200-350, 小型项目=50-200

                2. 更新质量 (0-300):
                   - 是否包含 breaking changes
                   - 新功能的创新性
                   - Bug 修复的重要性
                   参考: 重大版本=250+, 常规更新=100-250, 小补丁=30-100

                3. 信息完整度 (0-300):
                   - 是否有完整 Release Notes: %s
                   - 搜索到的信息渠道数量: %d
                   - 数据充分度评估: %s
                   - Commit 数量: %d
                   参考: 完整Release=250+, 仅Tag=100-150, 仅Commit=50-100

                技术: %s
                分类: %s
                版本: %s
                报道摘要(前300字): %s

                直接返回 JSON，不要其他文字。
                """.formatted(
                hasReleaseNotes, sourceCount, dataSufficiency, commitCount,
                tech.getName(), tech.getCategory(), searchResult.getDetectedVersion(),
                reportContent.length() > 300 ? reportContent.substring(0, 300) : reportContent);

        try {
            String response = chatModel.chat(prompt).trim();
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                response = response.substring(jsonStart, jsonEnd + 1);
            }

            JsonNode json = objectMapper.readTree(response);
            int total = json.has("total") ? json.get("total").asInt() : 500;
            return Math.max(0, Math.min(1000, total));
        } catch (Exception e) {
            log.warn("Tech index scoring failed for {}, using default 500", tech.getName(), e);
            return 500;
        }
    }
}
