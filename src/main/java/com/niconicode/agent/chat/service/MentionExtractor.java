package com.niconicode.agent.chat.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则型技术/项目提及抽取器。
 *
 * 目标：先把“确定性强”的来源（GitHub owner/repo、RSS/URL）和合理的项目关键词提取出来，
 * 便于后续写入 hot_topic 做热度统计。
 *
 * 注意：这是最小可用版本，避免引入大模型误判；后续可叠加 fastChatModel 做 NER。
 */
public final class MentionExtractor {

    private MentionExtractor() {
    }

    // github.com/owner/repo, or owner/repo
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "https?://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OWNER_REPO_PATTERN = Pattern.compile(
            "\\b([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)\\b");

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /**
     * 从自然语言里提取“像技术名/框架名”的英文 token（含 - . + #）。
     * 示例："vue/react/spring-boot/langchain4j/C#/next.js"
     */
    private static final Pattern TECH_TOKEN_PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9+.#-]{1,30})(?![A-Za-z0-9])");

    /**
     * 这些更像“概念/通用术语/模块名”，不应直接提升为热点技术。
     * 后续如果要统计“知识点热度”，建议单独一张表而不是 hot_topic。
     */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
        "aop", "ioc", "di", "mvc", "jwt", "orm", "sql", "http", "https", "tcp", "udp",
        "oauth", "oauth2", "rest", "graphql", "json", "xml", "html", "css", "js",
        "k8s", "ai", "ml", "llm", "rag"
    ));

    /**
     * 常见“技术/框架”白名单（可逐步扩展/迁移到 DB）。
     * 目的：让类似“vue/react”这种场景也能被识别出来。
     */
    private static final Set<String> ALLOW_LIST = new LinkedHashSet<>(Arrays.asList(
        "vue", "react", "angular", "svelte", "next", "nextjs", "nuxt", "vite",
        "spring", "springboot", "spring-boot", "quarkus", "micronaut",
        "django", "flask", "fastapi", "laravel", "rails",
        "kotlin", "java", "python", "golang", "rust", "typescript", "javascript",
        "docker", "kubernetes",
        "langchain", "langchain4j"
    ));

    /**
     * 对“强歧义词”做语境判定时，提取命中点附近的窗口大小（字符）。
     */
    private static final int CONTEXT_WINDOW_CHARS = 90;

    private static final List<WeightedTerm> FRONTEND_REACT_SIGNALS = List.of(
            // strong signals
            new WeightedTerm("jsx", 3),
            new WeightedTerm("hooks", 3),
            new WeightedTerm("component", 3),
            new WeightedTerm("dom", 3),
            new WeightedTerm("react-router", 3),
            new WeightedTerm("next.js", 3),
            new WeightedTerm("redux", 3),
            new WeightedTerm("vite", 3),
            new WeightedTerm("webpack", 3),
            new WeightedTerm("npm", 3),
            new WeightedTerm("yarn", 3),
            new WeightedTerm("pnpm", 3),
            // medium
            new WeightedTerm("frontend", 2),
            new WeightedTerm("前端", 2),
            new WeightedTerm("ui", 2),
            new WeightedTerm("页面", 2),
            new WeightedTerm("浏览器", 2),
            new WeightedTerm("spa", 2)
    );

    private static final List<WeightedTerm> AGENT_REACT_SIGNALS = List.of(
            // strong signals
            new WeightedTerm("agent", 3),
            new WeightedTerm("tools", 3),
            new WeightedTerm("tool", 3),
            new WeightedTerm("function calling", 3),
            new WeightedTerm("planner", 3),
            new WeightedTerm("reflect", 3),
            new WeightedTerm("reflection", 3),
            new WeightedTerm("reasoning", 3),
            new WeightedTerm("prompt", 3),
            // medium
            new WeightedTerm("langchain", 2),
            new WeightedTerm("langgraph", 2),
            new WeightedTerm("langchain4j", 2),
            new WeightedTerm("rag", 2),
            new WeightedTerm("retrieval", 2),
            new WeightedTerm("llm", 2),
            new WeightedTerm("模型", 2)
    );

    private static final int MIN_SCORE_TO_DECIDE = 3;
    private static final int MIN_DELTA_TO_DECIDE = 2;

    private enum ReactSense { FRONTEND, AGENT, UNKNOWN, AMBIGUOUS }

    private record WeightedTerm(String term, int weight) {}

    /**
     * 带诊断信息的抽取结果（用于 trace）。
     */
    public static final class MentionDiagnostics {
        public final List<String> mentions;
        public final List<String> diagnostics;

        MentionDiagnostics(List<String> mentions, List<String> diagnostics) {
            this.mentions = mentions;
            this.diagnostics = diagnostics;
        }
    }

    /**
     * 从文本中提取关键词（去重、保序）。
     */
    public static List<String> extractKeywords(String text, int maxKeywords) {
        return extractMentionsWithDiagnostics(text, maxKeywords).mentions;
    }

    /**
     * 提及抽取（带语境判定诊断信息）。
     * 诊断信息主要用于 trace 排查：例如 react 被判定为 FRONTEND/AGENT 的依据。
     */
    public static MentionDiagnostics extractMentionsWithDiagnostics(String text, int maxKeywords) {
        if (text == null || text.isBlank() || maxKeywords <= 0) {
            return new MentionDiagnostics(List.of(), List.of());
        }

        Set<String> out = new LinkedHashSet<>();

        List<String> diag = new ArrayList<>();

    // 0) 先把“强歧义词”的原文保护起来
    //    例如：ReAct（LLM 推理模式）与 React（前端框架）非常容易混。
    //    规则：
    //    - 出现 "ReAct"（大小写严格）时，记录为 "ReAct"（不转小写）
    //    - 小写/常规写法 "react" 记录为 "react"
    // 注意：ReAct 的强先验在 decideReactSense() 中通过窗口文本处理

        // 1) 尝试提取 owner/repo（优先 URL）
        Matcher m = GITHUB_URL_PATTERN.matcher(text);
        while (m.find()) {
            add(out, m.group(1) + "/" + m.group(2));
            if (out.size() >= maxKeywords) return new MentionDiagnostics(new ArrayList<>(out), diag);
        }

        // 2) 如果没有 GitHub URL，也允许从文本里抓 owner/repo 形态
        m = OWNER_REPO_PATTERN.matcher(text);
        while (m.find()) {
            String owner = m.group(1);
            String repo = m.group(2);
            // 过滤明显不是 repo 的路径（太短、全数字等）
            if (owner.length() < 2 || repo.length() < 2) continue;
            add(out, owner + "/" + repo);
            if (out.size() >= maxKeywords) return new MentionDiagnostics(new ArrayList<>(out), diag);
        }

        // 3) URL 作为补充（官网、RSS 等）。这里先全部收集，交给后续晋升/补全逻辑判断用途。
        m = URL_PATTERN.matcher(text);
        while (m.find()) {
            String raw = m.group();
            // 简单清理末尾标点
            raw = raw.replaceAll("[),.;\"']+$", "");
            try {
                URI uri = URI.create(raw);
                if (uri.getHost() != null) {
                    add(out, raw);
                }
            } catch (Exception ignored) {
                // ignore
            }
            if (out.size() >= maxKeywords) return new MentionDiagnostics(new ArrayList<>(out), diag);
        }

        // URL 抽取阶段的早返回也需要带诊断
        if (out.size() >= maxKeywords) {
            return new MentionDiagnostics(new ArrayList<>(out), diag);
        }

        // 4) 技术 token（英文/数字符号组合）抽取：覆盖 “vue/react 谁优谁劣” 这种场景
        //    注意：仅作为候选；会做 stop words & allow list 过滤，以及少量歧义消歧。
        m = TECH_TOKEN_PATTERN.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            if (token == null || token.isBlank()) continue;

            // 特殊消歧：ReAct（严格大小写）
            if ("ReAct".equals(token)) {
                add(out, "ReAct");
                diag.add("react-detect: token=ReAct => AGENT (case-sensitive)");
                if (out.size() >= maxKeywords) return new MentionDiagnostics(new ArrayList<>(out), diag);
                continue;
            }

            String norm = token.toLowerCase(Locale.ROOT);

            // react 是强歧义词：根据局部窗口做语境判定
            if ("react".equals(norm)) {
                int idx = m.start(1);
                ReactSense sense = decideReactSense(text, idx);
                if (sense == ReactSense.UNKNOWN || sense == ReactSense.AMBIGUOUS) {
                    diag.add("react-detect: token=react => " + sense + " (skip)");
                    continue;
                }
                if (sense == ReactSense.AGENT) {
                    // 语境更像 ReAct（agent）：用规范名 ReAct 记录
                    add(out, "ReAct");
                    diag.add("react-detect: token=react => AGENT (record as ReAct)");
                    if (out.size() >= maxKeywords) return new MentionDiagnostics(new ArrayList<>(out), diag);
                    continue;
                }
                // FRONTEND：正常记 react
                // 如果原文出现了 ReAct，并且也判定为前端，仍允许记录（因为窗口判定已足够强）
                diag.add("react-detect: token=react => FRONTEND");
            }

            if (STOP_WORDS.contains(norm)) continue;

            // 先用 allow list 提升精度，避免把各种普通英文单词当技术
            if (!ALLOW_LIST.contains(norm)) {
                // 少量启发式：xxx.js / xxxts / xxxai 之类也可能是项目名，但风险大，这里先不收
                continue;
            }

            // 统一归一：spring-boot / springboot
            if ("spring-boot".equals(norm)) norm = "springboot";

            add(out, norm);
            if (out.size() >= maxKeywords) return new MentionDiagnostics(new ArrayList<>(out), diag);
        }

        return new MentionDiagnostics(new ArrayList<>(out), diag);
    }

    private static ReactSense decideReactSense(String text, int tokenIndex) {
        // 1) 取局部窗口
        int start = Math.max(0, tokenIndex - CONTEXT_WINDOW_CHARS);
        int end = Math.min(text.length(), tokenIndex + CONTEXT_WINDOW_CHARS);
        String window = text.substring(start, end);
        String w = window.toLowerCase(Locale.ROOT);

        // 2) 强先验：若窗口内出现严格 ReAct，直接判定为 agent
        if (window.contains("ReAct")) {
            return ReactSense.AGENT;
        }

        int frontendScore = scoreSignals(w, FRONTEND_REACT_SIGNALS);
        int agentScore = scoreSignals(w, AGENT_REACT_SIGNALS);

        int max = Math.max(frontendScore, agentScore);
        int delta = Math.abs(frontendScore - agentScore);

        if (max < MIN_SCORE_TO_DECIDE) {
            // 特例：react.js / react-router 属于强前端信号
            if (w.contains("react.js") || w.contains("react-router")) {
                return ReactSense.FRONTEND;
            }
            return ReactSense.UNKNOWN;
        }

        if (delta < MIN_DELTA_TO_DECIDE) {
            return ReactSense.AMBIGUOUS;
        }

        return frontendScore > agentScore ? ReactSense.FRONTEND : ReactSense.AGENT;
    }

    private static int scoreSignals(String windowLower, List<WeightedTerm> signals) {
        int score = 0;
        for (WeightedTerm t : signals) {
            if (windowLower.contains(t.term())) {
                score += t.weight();
            }
        }
        return score;
    }

    private static void add(Set<String> out, String s) {
        if (s == null) return;
        String v = s.trim();
        if (v.isEmpty()) return;
        out.add(v);
    }
}
