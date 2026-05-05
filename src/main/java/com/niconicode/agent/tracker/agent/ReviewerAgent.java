package com.niconicode.agent.tracker.agent;

import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.tracker.service.TechIndexCalculator;
import com.niconicode.agent.chat.service.TraceLogger;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.niconicode.common.util.SafeTemplates;

/**
 * 审核 Agent
 * 审核报道质量 + 技术评分 + 发布决策
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent {

    private final ChatLanguageModel chatModel;
    private final TechIndexCalculator techIndexCalculator;
    private final TraceLogger traceLogger;

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
                                SearchAgent.SearchResult searchResult,
                                TraceLogger.TraceContext traceCtx) {
        ReviewResult result = new ReviewResult();

        // P0-K: 稀缺数据(OFFICIAL_URL)直接拒绝发布，保留为草稿。
        // 这种情况下报道内容由 WriterAgent 生成的占位 stub，没有实质技术分析，
        // 不应该自动公开。等用户改善 changelogUrl 配置后再生成正式报道。
        if (isThinData(searchResult)) {
            result.setApproved(false);
            result.setRevisedContent(reportContent);
            result.setTechIndex(0);
            result.setRejectionReason("仅检测到版本号变化，无具体变更内容（mode="
                    + searchResult.getUpdateMode() + "），保留为草稿");
            traceLogger.trace(traceCtx, "REVIEWER_DECISION",
                    "rejected: thin data, mode=" + searchResult.getUpdateMode());
            return result;
        }

        // Step 1: 内容审核
        long reviewStart = System.currentTimeMillis();
        String contentReview = reviewContent(reportContent);
        int reviewDuration = (int)(System.currentTimeMillis() - reviewStart);
        boolean contentModified = !contentReview.equals(reportContent);
        traceLogger.trace(traceCtx, "REVIEWER_CONTENT_REVIEW",
                "modified=" + contentModified + ", duration=" + reviewDuration + "ms");
        result.setRevisedContent(contentReview);

        // Step 2: 技术指数评分（使用新的计算标准）
        int techIndex = techIndexCalculator.calculateTechIndex(tech);
        traceLogger.trace(traceCtx, "REVIEWER_TECH_INDEX", "score=" + techIndex);
        result.setTechIndex(techIndex);

        // Step 3: 低质量处理
        if (searchResult.getDataSufficiency() == SearchAgent.DataSufficiency.LOW) {
            int cappedIndex = Math.min(techIndex, 200);
            traceLogger.trace(traceCtx, "REVIEWER_LOW_QUALITY",
                    "original=" + techIndex + ", capped=" + cappedIndex);
            result.setTechIndex(cappedIndex);
            result.setQualityNotes("数据来源有限，技术指数已压低");
        }

        // Step 4: 极低质量拒绝
        if (techIndex < 50 && searchResult.getDataSufficiency() == SearchAgent.DataSufficiency.LOW) {
            result.setApproved(false);
            result.setRejectionReason("数据不足且技术指数过低");
            traceLogger.trace(traceCtx, "REVIEWER_DECISION", "rejected: " + result.getRejectionReason());
        } else {
            traceLogger.trace(traceCtx, "REVIEWER_DECISION",
                    "approved, techIndex=" + result.getTechIndex());
        }

        return result;
    }

    /**
     * P0-K: 与 WriterAgent.isThinData 保持一致 - 判断 search result 是否为稀缺数据。
     * 稀缺数据的报道由 WriterAgent 生成的占位 stub，不应自动发布。
     */
    private boolean isThinData(SearchAgent.SearchResult sr) {
        if (sr == null) return true;
        if ("OFFICIAL_URL".equals(sr.getUpdateMode())) return true;
        boolean hasReleaseBody = sr.getReleaseInfo() != null
                && sr.getReleaseInfo().getBody() != null
                && !sr.getReleaseInfo().getBody().isBlank();
        boolean hasCommits = sr.getRecentCommits() != null && !sr.getRecentCommits().isEmpty();
        boolean hasChangelog = sr.getChangelogEntries() != null && !sr.getChangelogEntries().isEmpty();
        boolean hasRssContent = sr.getRssContent() != null && !sr.getRssContent().isBlank();
        return !hasReleaseBody && !hasCommits && !hasChangelog && !hasRssContent;
    }

    /**
     * 内容审核：检查敏感信息、虚假信息、不当用语
     * 返回修订后的内容（或原内容如果无需修改）
     */
    private String reviewContent(String reportContent) {
    // 注意：不要使用 """.formatted(reportContent)
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
        """ + SafeTemplates.s(reportContent);

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
}

