package com.niconicode.agent.chat.service;

import com.niconicode.agent.chat.dto.IntentClassification;
import com.niconicode.agent.chat.dto.IntentClassification.ClassifiedBy;
import com.niconicode.agent.chat.dto.IntentClassification.Intent;
import com.niconicode.agent.chat.dto.IntentClassification.SubIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L1 规则引擎 — 纯正则/关键词匹配，<1ms 延迟
 */
@Slf4j
@Component
public class RuleBasedClassifier {

    private final List<ClassificationRule> rules;

    public RuleBasedClassifier() {
        this.rules = buildRules();
    }

    /**
     * 尝试规则匹配，返回 null 表示无法分类
     */
    public IntentClassification classify(String message) {
        if (message == null || message.isBlank()) return null;
        String trimmed = message.trim();

        for (ClassificationRule rule : rules) {
            Matcher matcher = rule.pattern.matcher(trimmed);
            if (matcher.find()) {
                Map<String, String> slots = rule.slotExtractor != null
                        ? rule.slotExtractor.extract(matcher, trimmed) : new HashMap<>();
                return IntentClassification.builder()
                        .primaryIntent(rule.intent)
                        .subIntent(rule.subIntent)
                        .confidence(rule.confidence)
                        .slots(slots)
                        .classifiedBy(ClassifiedBy.RULE)
                        .build();
            }
        }
        return null;
    }

    // ---- Rule definitions ----

    private static List<ClassificationRule> buildRules() {
        List<ClassificationRule> rules = new ArrayList<>();

        // === GENERAL_CHAT (conf=0.95) ===
        rules.add(rule(
                "^(你好|您好|hi|hello|hey|嗨|哈[喽啰]|早[上啊]?好?|晚[上安]好?|下午好)([!！。.~]*)?$",
                Intent.GENERAL_CHAT, SubIntent.GREETING, 0.95, Pattern.CASE_INSENSITIVE));
        rules.add(rule(
                "^(再见|拜[拜了]|bye|goodbye|see\\s*you|回见|下次见)([!！。.~]*)?$",
                Intent.GENERAL_CHAT, SubIntent.FAREWELL, 0.95, Pattern.CASE_INSENSITIVE));
        rules.add(rule(
                "^(谢谢|感谢|thanks|thank\\s*you|thx|多谢|太[谢棒好]了)([!！。.~]*)?$",
                Intent.GENERAL_CHAT, SubIntent.THANKS, 0.95, Pattern.CASE_INSENSITIVE));
        rules.add(rule(
                "^(好的|ok|okay|嗯|嗯嗯|明白了?|了解了?|知道了?|收到|可以|行|对|是的|没问题|好[嘞吧呀的]?)([!！。.~]*)?$",
                Intent.GENERAL_CHAT, SubIntent.CONFIRMATION, 0.95, Pattern.CASE_INSENSITIVE));
        rules.add(rule(
                "^(你是谁|你是什么|你叫什么|你能做什么|介绍一?下你自己|what\\s+are\\s+you)([?？!！。.]*)?$",
                Intent.GENERAL_CHAT, SubIntent.ABOUT_BOT, 0.95, Pattern.CASE_INSENSITIVE));

        // === GITHUB_ANALYSIS (conf=0.90) — URL 匹配 ===
        rules.add(new ClassificationRule(
                Pattern.compile("github\\.com/([\\w.-]+)/([\\w.-]+)", Pattern.CASE_INSENSITIVE),
                Intent.GITHUB_ANALYSIS, SubIntent.REPO_OVERVIEW, 0.90,
                (m, msg) -> {
                    Map<String, String> slots = new HashMap<>();
                    slots.put("owner", m.group(1));
                    slots.put("repoName", m.group(2));
                    slots.put("repoUrl", m.group(0));
                    // 细分子意图
                    return slots;
                }));

        // === COMPARISON (conf=0.85) ===
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:vs\\.?|VS\\.?|versus|对比|和|与|还是|跟)\\s*(.+?)\\s*(?:哪个好|怎么选|区别|对比|比较|好|优劣)?[?？。!！]*$"),
                Intent.COMPARISON, SubIntent.HEAD_TO_HEAD, 0.85,
                (m, msg) -> {
                    Map<String, String> slots = new HashMap<>();
                    slots.put("techA", m.group(1).trim());
                    slots.put("techB", m.group(2).trim());
                    return slots;
                }));
        rules.add(new ClassificationRule(
                Pattern.compile("从\\s*(.+?)\\s*迁移到\\s*(.+?)"),
                Intent.COMPARISON, SubIntent.MIGRATION, 0.85,
                (m, msg) -> {
                    Map<String, String> slots = new HashMap<>();
                    slots.put("techA", m.group(1).trim());
                    slots.put("techB", m.group(2).trim());
                    return slots;
                }));
        rules.add(rule("(?:做|实现|开发|搭建).+(?:用什么|选什么|推荐).+(?:技术|框架|语言|工具|库)",
                Intent.COMPARISON, SubIntent.SELECTION, 0.85));

        // === VERSION_UPDATE (conf=0.85) ===
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:最新版本|最新版|新版本|latest\\s*version)", Pattern.CASE_INSENSITIVE),
                Intent.VERSION_UPDATE, SubIntent.LATEST_VERSION, 0.85,
                (m, msg) -> Map.of("techName", m.group(1).trim())));
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:更新了什么|有什么更新|changelog|变更日志|更新日志)", Pattern.CASE_INSENSITIVE),
                Intent.VERSION_UPDATE, SubIntent.CHANGELOG, 0.85,
                (m, msg) -> Map.of("techName", m.group(1).trim())));
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:发布了|release|发版|新发布|发布了什么)", Pattern.CASE_INSENSITIVE),
                Intent.VERSION_UPDATE, SubIntent.RELEASE_NOTE, 0.85,
                (m, msg) -> Map.of("techName", m.group(1).trim())));

        // === REPORT_QUERY (conf=0.85) ===
        rules.add(rule("(?:今天|最近|这几天).*(?:有什么|有哪些)?.*(?:报道|新闻|资讯|动态)",
                Intent.REPORT_QUERY, SubIntent.LATEST_NEWS, 0.85));
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:最近|最新).*(?:新闻|报道|动态|资讯)"),
                Intent.REPORT_QUERY, SubIntent.TECH_NEWS, 0.85,
                (m, msg) -> Map.of("techName", m.group(1).trim())));
        rules.add(rule("(?:热门|流行|火|trending|趋势).*(?:技术|框架|项目|开源)",
                Intent.REPORT_QUERY, SubIntent.TRENDING, 0.85));

        // === CODE_HELP (conf=0.80) ===
        rules.add(new ClassificationRule(
                Pattern.compile("(?:帮我写|写一[个段]|帮我实现|帮我生成|写个|帮忙写)\\s*(.*)"),
                Intent.CODE_HELP, SubIntent.WRITE_CODE, 0.80,
                (m, msg) -> {
                    Map<String, String> slots = new HashMap<>();
                    slots.put("task", m.group(1).trim());
                    return slots;
                }));
        rules.add(rule("(?:这段|这个).*(?:代码|程序).*(?:什么意思|干什么|做什么|作用)",
                Intent.CODE_HELP, SubIntent.EXPLAIN_CODE, 0.80));
        rules.add(rule("(?:代码|程序).*(?:为什么|怎么).*(?:报错|出错|异常|失败|不行|bug)",
                Intent.CODE_HELP, SubIntent.DEBUG, 0.80));
        rules.add(rule("(?:最佳实践|best\\s*practice|推荐做法|正确的?写法|规范)", Pattern.CASE_INSENSITIVE,
                Intent.CODE_HELP, SubIntent.BEST_PRACTICE, 0.80));

        // === TECH_QUERY (conf=0.75) — 较宽泛 ===
        rules.add(new ClassificationRule(
                Pattern.compile("(?:什么是|介绍[一下]*|了解[一下]*)\\s*(.+?)\\s*[?？。]*$"),
                Intent.TECH_QUERY, SubIntent.CONCEPT, 0.75,
                (m, msg) -> Map.of("techName", m.group(1).trim())));
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:怎么配置|如何配置|配置方法|怎么设置|如何设置)"),
                Intent.TECH_QUERY, SubIntent.CONFIGURATION, 0.75,
                (m, msg) -> Map.of("techName", m.group(1).trim())));
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:有什么特性|特性|功能|特点|feature)", Pattern.CASE_INSENSITIVE),
                Intent.TECH_QUERY, SubIntent.FEATURE, 0.75,
                (m, msg) -> Map.of("techName", m.group(1).trim())));
        rules.add(new ClassificationRule(
                Pattern.compile("(.+?)\\s*(?:报错|出错|异常|失败|不[行能]|error|exception)", Pattern.CASE_INSENSITIVE),
                Intent.TECH_QUERY, SubIntent.TROUBLESHOOT, 0.75,
                (m, msg) -> Map.of("techName", m.group(1).trim())));

        return rules;
    }

    // ---- Rule helpers ----

    private static ClassificationRule rule(String regex, Intent intent, SubIntent sub, double conf) {
        return new ClassificationRule(Pattern.compile(regex), intent, sub, conf, null);
    }

    private static ClassificationRule rule(String regex, Intent intent, SubIntent sub, double conf, int flags) {
        return new ClassificationRule(Pattern.compile(regex, flags), intent, sub, conf, null);
    }

    private static ClassificationRule rule(String regex, int flags, Intent intent, SubIntent sub, double conf) {
        return new ClassificationRule(Pattern.compile(regex, flags), intent, sub, conf, null);
    }

    // ---- Inner types ----

    private static class ClassificationRule {
        final Pattern pattern;
        final Intent intent;
        final SubIntent subIntent;
        final double confidence;
        final SlotExtractor slotExtractor;

        ClassificationRule(Pattern pattern, Intent intent, SubIntent subIntent,
                          double confidence, SlotExtractor slotExtractor) {
            this.pattern = pattern;
            this.intent = intent;
            this.subIntent = subIntent;
            this.confidence = confidence;
            this.slotExtractor = slotExtractor;
        }
    }

    @FunctionalInterface
    private interface SlotExtractor {
        Map<String, String> extract(Matcher matcher, String fullMessage);
    }
}
