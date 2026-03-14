package com.niconicode.agent.tracker.agent;

import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.entity.TrackedTech;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 撰写 Agent
 * 根据 SearchAgent 提供的信息撰写高质量技术报道
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriterAgent {

    private final ChatLanguageModel chatModel;

    /**
     * 根据搜索结果撰写技术报道
     */
    public String write(TrackedTech tech, SearchAgent.SearchResult searchResult) {
        String prompt = buildPrompt(tech, searchResult);

        try {
            String content = chatModel.chat(prompt);
            return cleanMarkdownContent(content);
        } catch (Exception e) {
            log.error("WriterAgent failed for {}", tech.getName(), e);
            throw new RuntimeException("报道撰写失败: " + e.getMessage());
        }
    }

    private String buildPrompt(TrackedTech tech, SearchAgent.SearchResult searchResult) {
        StringBuilder context = new StringBuilder();
        context.append("技术: ").append(tech.getName()).append("\n");
        context.append("分类: ").append(tech.getCategory()).append("\n");
        context.append("检测到的版本: ").append(searchResult.getDetectedVersion()).append("\n");
        context.append("更新检测模式: ").append(searchResult.getUpdateMode()).append("\n");

        if (searchResult.getReleaseInfo() != null) {
            var info = searchResult.getReleaseInfo();
            context.append("发布日期: ").append(info.getPublishedAt() != null ? info.getPublishedAt() : "未知").append("\n");
            context.append("Release 页面: ").append(info.getHtmlUrl() != null ? info.getHtmlUrl() : "").append("\n");
            if (info.getBody() != null && !info.getBody().isBlank()) {
                String body = info.getBody();
                context.append("Release Notes:\n").append(
                        body.length() > 3000 ? body.substring(0, 3000) : body).append("\n");
            }
        }

        if (!searchResult.getRecentCommits().isEmpty()) {
            context.append("\n近期 Commits (").append(searchResult.getRecentCommits().size()).append(" 条):\n");
            context.append(searchResult.getRecentCommits().stream()
                    .map(c -> {
                        String sha = c.getSha().length() > 7 ? c.getSha().substring(0, 7) : c.getSha();
                        String msg = c.getMessage() != null ? c.getMessage().split("\n")[0] : "";
                        return "- " + sha + ": " + msg;
                    })
                    .collect(Collectors.joining("\n")));
            context.append("\n");
        }

        if (searchResult.getRawInfoSummary() != null) {
            context.append("\nAI 信息摘要: ").append(searchResult.getRawInfoSummary()).append("\n");
        }

        context.append("\n来源链接:\n");
        for (String url : searchResult.getSourceUrls()) {
            context.append("- ").append(url).append("\n");
        }

        String dataNote = switch (searchResult.getDataSufficiency()) {
            case HIGH -> "信息充分，请撰写详尽的技术分析报告。";
            case MEDIUM -> "信息中等，请基于已有数据撰写报告，不足之处注明。";
            case LOW -> "信息有限（仅 Commit 活动），请如实说明，不要虚构细节。在报道中添加提示：'本次更新可能不是正式发布版本'。";
        };

        return """
                你是一位技术分析师，负责撰写技术更新分析报告。

                严格禁止：
                - 不要使用"业内专家指出"、"据了解"、"值得一提的是"、"值得关注的是"等新闻腔调
                - 不要虚构任何信息，所有内容必须基于提供的原始数据
                - 不要使用修辞性的开场或结尾
                - 不要使用"笔者"、"本报"等自称

                必须包含：
                - 基于事实的技术分析和影响评估
                - "AI 建议"段落（如升级建议、兼容性注意事项）
                - "技术背景"段落（该技术的定位和生态）
                - 所有来源链接

                格式要求：
                - Markdown 格式
                - 结构：版本概要 → 更新详解 → 影响分析 → AI 建议 → 技术背景 → 来源链接
                - 直接输出 Markdown 内容，不要用 ```markdown ``` 包裹

                数据充分度说明: %s

                原始数据:
                %s
                """.formatted(dataNote, context.toString());
    }

    private String cleanMarkdownContent(String content) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.startsWith("```markdown") || trimmed.startsWith("```md")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0 && trimmed.endsWith("```")) {
                trimmed = trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        } else if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length() > 6) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }
}
