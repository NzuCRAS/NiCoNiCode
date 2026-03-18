package com.niconicode.agent.tracker.agent;

import com.niconicode.agent.tracker.dto.GitHubCommitInfo;
import com.niconicode.agent.tracker.entity.TrackedTech;
import com.niconicode.agent.chat.service.TraceLogger;
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
    private final TraceLogger traceLogger;

    /**
     * P2-N: 撰写结果，同时包含 AI 生成的标题和正文内容
     */
    @lombok.Data
    public static class WriteResult {
        private final String title;
        private final String content;
    }

    /**
     * 根据搜索结果撰写技术报道，返回 AI 生成的标题和正文
     */
    public WriteResult write(TrackedTech tech, SearchAgent.SearchResult searchResult,
                             TraceLogger.TraceContext traceCtx) {
        String prompt = buildPrompt(tech, searchResult);
        traceLogger.trace(traceCtx, "WRITER_PROMPT_BUILT", "contextLength=" + prompt.length());

        try {
            long aiStart = System.currentTimeMillis();
            String rawContent = chatModel.chat(prompt);
            int aiDuration = (int)(System.currentTimeMillis() - aiStart);
            traceLogger.trace(traceCtx, "WRITER_AI_CALL", "outputLength=" + rawContent.length()
                    + ", duration=" + aiDuration + "ms");

            String cleaned = cleanMarkdownContent(rawContent);
            traceLogger.trace(traceCtx, "WRITER_CONTENT_CLEAN",
                    "before=" + rawContent.length() + ", after=" + cleaned.length());

            // P2-N: 从 AI 正文中提取第一个 # 标题作为报道标题
            String title = extractTitle(cleaned, tech, searchResult);
            traceLogger.trace(traceCtx, "WRITER_TITLE", "title=" + title);

            return new WriteResult(title, cleaned);
        } catch (Exception e) {
            log.error("WriterAgent failed for {}", tech.getName(), e);
            traceLogger.traceError(traceCtx, "WRITER_AI_CALL", e);
            throw new RuntimeException("报道撰写失败: " + e.getMessage());
        }
    }

    /**
     * P2-N: 从 AI 生成的 Markdown 正文中提取标题。
     * 优先使用正文第一行的 # 一级标题；
     * 其次用 ## 二级标题的第一行；
     * 都没有则降级为机械拼接（保底）。
     */
    private String extractTitle(String content, TrackedTech tech, SearchAgent.SearchResult searchResult) {
        if (content != null && !content.isBlank()) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("# ") && trimmed.length() > 2) {
                    return trimmed.substring(2).trim();
                }
            }
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("## ") && trimmed.length() > 3) {
                    return trimmed.substring(3).trim();
                }
            }
        }
        // 降级：机械拼接（保底，避免 NPE）
        return tech.getName() + " " + searchResult.getDetectedVersion()
                + " " + getModeLabel(searchResult.getUpdateMode());
    }

    private String getModeLabel(String mode) {
        return switch (mode != null ? mode : "") {
            case "RELEASE" -> "发布";
            case "TAG" -> "发布 (Tag)";
            case "COMMIT" -> "开发动态";
            case "RSS" -> "更新公告";
            case "OFFICIAL_URL" -> "版本更新";
            default -> "更新";
        };
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
                你是一位资深技术分析师，负责撰写详尽、充实、有深度的技术更新分析报告。
                你的读者是开发者和技术决策者，他们需要全面了解这次更新的每一个细节。

                严格禁止：
                - 不要使用"业内专家指出"、"据了解"、"值得一提的是"、"值得关注的是"等新闻腔调
                - 不要虚构任何信息，所有内容必须基于提供的原始数据
                - 不要使用修辞性的开场或结尾
                - 不要使用"笔者"、"本报"等自称
                - 不要写空洞的总结段落

                必须包含的内容段落（每个段落都要有实质内容，不能敷衍）：

                ## 版本概要
                简明扼要地说明本次更新的版本号、发布日期、更新类型（大版本/小版本/补丁）。

                ## 核心变更详解
                **逐条列出**本次更新的所有变更点，每个变更点都要：
                - 说明变更的具体内容（是什么）
                - 说明变更的技术原因（为什么）
                - 说明对使用者的影响（影响谁、怎么影响）
                如果 Release Notes 中列出了多个变更，**每一个都要讲解**，不能遗漏或笼统带过。

                ## 破坏性变更与迁移指南
                如果存在 Breaking Changes，必须明确列出并给出迁移建议。
                如果没有，也要明确说明"本次更新无破坏性变更"。

                ## 性能与安全改进
                列出本次更新中涉及的性能优化和安全修复（如有）。

                ## AI 建议
                - 是否建议立即升级，还是观望
                - 升级时需要注意的兼容性问题
                - 对不同规模项目的建议（个人项目 vs 生产环境）

                ## 技术背景
                简要介绍该技术在生态中的定位、主要竞品、适用场景。

                ## 来源链接
                列出所有来源 URL，使用 Markdown 链接格式。

                格式要求：
                - Markdown 格式，直接输出内容，不要用 ```markdown ``` 包裹
                - **第一行必须是 `# 报道标题`（一级标题），标题要简洁有力、体现核心价值，不能是机械的"技术名+版本号"**
                - 标题示例（好）："Spring Boot 3.4 正式发布：虚拟线程全面 GA，启动速度再提 40%"
                - 标题反例（差）："Spring Boot 3.4.0 发布"
                - 其余章节使用 ## 二级标题
                - 重点内容使用**加粗**
                - 变更列表使用有序或无序列表
                - 代码或配置变更使用代码块
                - 内容要充实详尽，不要惜字如金

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
