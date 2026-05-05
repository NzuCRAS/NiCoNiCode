package com.niconicode.agent.tracker.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 官方网站更新日志爬虫
 * 从大模型/技术官网的更新日志/公告页面提取结构化更新信息
 *
 * <p>支持的页面结构：</p>
 * <ul>
 *   <li>article/section 标签包裹的更新条目（如 DeepSeek API 文档更新页）</li>
 *   <li>按 h1/h2/h3 标题分隔的更新区块</li>
 *   <li>带特定 class 的更新列表（.update, .changelog, .release-note 等）</li>
 * </ul>
 */
@Slf4j
@Service
public class OfficialSiteCrawler {

    /**
     * 通用版本号正则：匹配 v1.2.3 / 1.2.3 / Release 4.0 等格式
     */
    private static final Pattern GENERIC_VERSION_PATTERN =
            Pattern.compile("(?:v|version\\s*|release\\s*)(\\d+\\.\\d+(?:\\.\\d+)*(?:[-.][a-zA-Z0-9]+)?)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * 大模型版本号正则：匹配 DeepSeek-V3, GPT-4o, Claude 3.5 Sonnet/4.5, Gemini 2.0 Flash 等
     */
    private static final Pattern MODEL_VERSION_PATTERN = Pattern.compile(
            "(DeepSeek[- ]?[VRv]?\\d+(?:[-.]\\d+)?(?:[-.]?[a-zA-Z0-9]+)?|"
                    + "GPT[- ]?4[0-9a-z]*(?:[- ]?[o\\d.]+)?|"
                    + "Claude[- ]?\\d+(?:\\.\\d+)?(?:[- ]?(?:Sonnet|Opus|Haiku))?|"
                    + "Gemini[- ]?(?:\\d+\\.\\d+|Pro|Ultra|Flash|Nano|Advanced)?|"
                    + "Qwen[- ]?(?:\\d+[-.]?\\d*(?:[-]?\\w+)?)|"
                    + "Llama[- ]?(?:\\d+[-.]?\\d*(?:[-]?\\w+)?)|"
                    + "Mixtral[- ]?(?:\\d+x\\d+[Bb]?)|"
                    + "Kimi[- ]?(?:k\\d+)?|"
                    + "GLM[- ]?\\d+|"
                    + "ERNIE[- ]?(?:\\d+\\.\\d+|[A-Za-z]+)|"
                    + "书生[- ]?(?:浦语|InternLM)[- ]?\\d+?|"
                    + "通义[- ]?(?:千问)?[- ]?\\d+?|"
                    + "Baichuan[- ]?\\d+|"
                    + "ChatGLM[- ]?\\d+?|"
                    + "文心[- ]?(?:一言)?[- ]?\\d+?|"
                    + "星火[- ]?(?:认知)?[- ]?\\d+?|"
                    + "智谱[- ]?(?:清言)?[- ]?\\d+?)",
            Pattern.CASE_INSENSITIVE);

    /** HTTP 超时时间 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    /** 静态 HttpClient：复用连接 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 解析时最多提取的条目数 */
    private static final int MAX_ENTRIES = 10;

    /** 内容截断长度 */
    private static final int CONTENT_TRUNCATE = 3000;

    @Data
    public static class ChangelogEntry {
        private String title;
        private String version;
        private String date;
        private String content;
        private String htmlContent;
        private String link;
        private boolean isLatest;
    }

    /**
     * 抓取并解析更新日志页面
     *
     * @param changelogUrl 更新日志页面 URL（如 https://api-docs.deepseek.com/zh-cn/updates）
     * @return 更新条目列表，最新的排在最前面；如果抓取失败或无有效条目则返回空列表
     */
    public List<ChangelogEntry> crawlChangelog(String changelogUrl) {
        if (changelogUrl == null || changelogUrl.isBlank()) {
            return List.of();
        }

        log.debug("Crawling changelog from: {}", changelogUrl);

        try {
            String html = fetchHtml(changelogUrl);
            if (html == null || html.isBlank()) {
                log.warn("Empty response from changelog URL: {}", changelogUrl);
                return List.of();
            }

            List<ChangelogEntry> entries = parseChangelog(html, changelogUrl);
            log.debug("Parsed {} changelog entries from {}", entries.size(), changelogUrl);
            return entries;

        } catch (Exception e) {
            log.warn("Failed to crawl changelog from {}: {}", changelogUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取页面 HTML 内容
     */
    private String fetchHtml(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("HTTP {} from {}", response.statusCode(), url);
            return null;
        }

        return response.body();
    }

    /**
     * 解析 HTML，提取更新条目列表
     *
     * <p>解析策略（按优先级）：</p>
     * <ol>
     *   <li>尝试常见的更新日志容器选择器（#content-container / article / section 等）</li>
     *   <li>如果容器策略失败，退回到按 h2/h3 标题分割页面</li>
     *   <li>尝试 Hero/Featured Article 结构（Google Blog 等单篇博客列表页）</li>
     *   <li>尝试列表结构（ul/ol > li）</li>
     * </ol>
     */
    List<ChangelogEntry> parseChangelog(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);

        // 移除导航、脚本、样式等干扰元素（扩大范围以处理更多 CMS）
        doc.select("nav, script, style, noscript, link, meta, .nav, .navbar, .sidebar, .menu, "
                + ".breadcrumbs, .toc, footer, .footer").remove();

        // 策略1：尝试常见的更新日志容器
        List<ChangelogEntry> entries = tryContainerStrategy(doc, baseUrl);

        if (entries.isEmpty()) {
            // 策略2：按 heading 分割（日期型 / 版本型）
            entries = tryHeadingStrategy(doc, baseUrl);
        }

        if (entries.isEmpty()) {
            // 策略3：Featured / Hero 文章结构（Gemini 博客页等）
            entries = tryFeaturedArticleStrategy(doc, baseUrl);
        }

        if (entries.isEmpty()) {
            // 策略4：尝试列表结构（ul/ol > li）
            entries = tryListStrategy(doc, baseUrl);
        }

        // 标记第一条为最新
        if (!entries.isEmpty()) {
            entries.get(0).setLatest(true);
        }

        // 限制条目数
        if (entries.size() > MAX_ENTRIES) {
            return entries.subList(0, MAX_ENTRIES);
        }
        return entries;
    }

    /**
     * 策略1：从 article/section/.update/.changelog 等容器中提取条目
     */
    private List<ChangelogEntry> tryContainerStrategy(Document doc, String baseUrl) {
        List<ChangelogEntry> entries = new ArrayList<>();

        // 按优先级尝试多种选择器
        String[] selectors = {
                // Claude Platform release notes (content-container article)
                "#content-container",
                // DeepSeek API 文档风格
                "main article", "main section",
                // 常见 CMS 风格
                ".updates > *", ".changelog > *", ".release-notes > *",
                ".update-list > *", ".changelog-list > *", ".release-list > *",
                // 带 class 的条目
                ".update", ".changelog-item", ".release-note", ".release-item",
                ".version-item", ".news-item", ".announcement",
                // 通用 section（排除过浅的层级）
                "body > main > section", "body > main > article",
                ".content > section", ".content > article",
                ".doc-content > *", ".documentation > *"
        };

        for (String selector : selectors) {
            Elements elements = doc.select(selector);
            if (elements.size() >= 2) {
                for (Element el : elements) {
                    ChangelogEntry entry = extractEntry(el, baseUrl);
                    if (entry != null && isValidEntry(entry)) {
                        entries.add(entry);
                    }
                }
                if (!entries.isEmpty()) {
                    log.debug("Container strategy matched selector '{}', found {} entries", selector, entries.size());
                    break;
                }
            }
        }

        return entries;
    }

    /**
     * 策略2：按 h2/h3 标题将页面分割成多个区块。
     * 支持日期型标题（如 "April 30, 2026"）和版本型标题（如 "DeepSeek-V4"）。
     * 自动过滤导航栏/页脚中的非 changelog heading。
     */
    private List<ChangelogEntry> tryHeadingStrategy(Document doc, String baseUrl) {
        List<ChangelogEntry> entries = new ArrayList<>();

        // 获取所有 h2/h3
        Elements headings = doc.select("h2, h3");

        for (int i = 0; i < headings.size(); i++) {
            Element heading = headings.get(i);
            String title = heading.text().trim();

            // 跳过页面级标题
            if (isPageLevelTitle(title)) {
                continue;
            }

            // 跳过导航/页脚类 heading（"Solutions"、"Partners" 等）
            if (isNavLikeHeading(title)) {
                continue;
            }

            // 收集该 heading 到下一个 heading 之间的所有内容
            StringBuilder contentBuilder = new StringBuilder();
            StringBuilder htmlBuilder = new StringBuilder();

            Element sibling = heading.nextElementSibling();
            while (sibling != null && !isHeading(sibling)) {
                String text = sibling.text().trim();
                if (!text.isEmpty()) {
                    if (contentBuilder.length() > 0) contentBuilder.append("\n");
                    contentBuilder.append(text);
                    htmlBuilder.append(sibling.outerHtml());
                }
                sibling = sibling.nextElementSibling();
            }

            if (contentBuilder.length() == 0) {
                continue;
            }

            // 版本提取：先尝试 model/generic 版本号，再尝试把标题当作日期
            String version = extractVersion(title);
            if (version == null) {
                version = extractDate(title); // "April 30, 2026" 等日期型标题作为版本标识
            }
            String date = extractDate(title + " " + contentBuilder);

            ChangelogEntry entry = new ChangelogEntry();
            entry.setTitle(title);
            entry.setVersion(version);
            entry.setDate(date);
            entry.setContent(truncate(contentBuilder.toString(), CONTENT_TRUNCATE));
            entry.setHtmlContent(htmlBuilder.toString());
            entry.setLink(extractLink(heading, baseUrl));

            if (isValidEntry(entry)) {
                entries.add(entry);
            }
        }

        log.debug("Heading strategy found {} entries", entries.size());
        return entries;
    }

    /**
     * 策略3：Featured / Hero 文章结构（Google Blog、Apple Developer News 等）。
     * 页面通常不是传统 changelog，而是单篇最新文章放在 hero/featured 区域。
     * 我们通过查找 .featured-article / hero 区域内的 heading + description + link 来构造一个条目。
     */
    private List<ChangelogEntry> tryFeaturedArticleStrategy(Document doc, String baseUrl) {
        List<ChangelogEntry> entries = new ArrayList<>();

        // 常见 featured-article / hero 选择器（按优先级）
        String[] heroSelectors = {
                // Google Blog 风格
                "section[class*='featured-article']",
                "section[class*='hero']",
                ".featured-article",
                ".article-hero",
                ".hero__section",
                // Medium / Substack 风格
                "article[class*='featured']",
                "[class*='latest-post']",
                "[class*='newest-article']",
                // 通用
                ".latest-news .article:first-child",
                ".news-grid article:first-child"
        };

        for (String selector : heroSelectors) {
            Element hero = doc.selectFirst(selector);
            if (hero == null) continue;

            // 提取标题：优先 h1/h2/h3，其次 aria-label，最后取首段
            String title = null;
            for (String h : new String[]{"h1", "h2", "h3"}) {
                Element hEl = hero.selectFirst(h);
                if (hEl != null) {
                    title = hEl.text().trim();
                    if (!title.isBlank() && title.length() > 5) break;
                }
            }
            if (title == null || title.isBlank()) {
                Element ariaEl = hero.selectFirst("[aria-label]");
                if (ariaEl != null) {
                    title = ariaEl.attr("aria-label").trim();
                }
            }
            if (title == null || title.isBlank()) {
                String text = hero.text().trim();
                int nl = text.indexOf('\n');
                if (nl > 5 && nl < 300) title = text.substring(0, nl).trim();
                else title = text.length() <= 300 ? text : text.substring(0, 300);
            }

            // 提取描述：第一个有意义的 p 或 div
            String content = "";
            for (String descSel : new String[]{"p", "[class*='description']", "[class*='summary']", "[class*='excerpt']", "div"}) {
                Element descEl = hero.selectFirst(descSel);
                if (descEl != null) {
                    String t = descEl.text().trim();
                    if (t.length() > 20 && !t.equals(title)) {
                        content = t;
                        break;
                    }
                }
            }

            // 提取链接：优先取指向内页的 a（排除社交/外部链接）
            String link = null;
            for (Element a : hero.select("a[href]")) {
                String href = a.attr("abs:href");
                // 跳过纯锚点、社交链接
                if (href != null && !href.isBlank()
                        && !href.startsWith("#")
                        && !href.contains("twitter.com")
                        && !href.contains("facebook.com")
                        && !href.contains("linkedin.com")) {
                    link = href;
                    break; // 取第一个
                }
            }

            // 提取版本 / 日期
            String version = extractVersion(title);
            if (version == null) {
                version = extractDate(title);
            }
            String date = extractDate(title + " " + content);

            if (title != null && !title.isBlank() && title.length() > 5) {
                ChangelogEntry entry = new ChangelogEntry();
                entry.setTitle(title);
                entry.setVersion(version);
                entry.setDate(date);
                entry.setContent(truncate(content, CONTENT_TRUNCATE));
                entry.setLink(link);
                entry.setHtmlContent(hero.html());
                entries.add(entry);
                log.debug("Featured article strategy matched selector '{}', title='{}'", selector, title);
                break; // 只取第一个 hero
            }
        }

        return entries;
    }

    /**
     * 策略4：从列表结构（ul/ol > li）中提取
     */
    private List<ChangelogEntry> tryListStrategy(Document doc, String baseUrl) {
        List<ChangelogEntry> entries = new ArrayList<>();

        // 找包含更新相关 class 的列表
        Elements lists = doc.select("ul.updates, ol.updates, ul.changelog, ol.changelog, ul.news, ol.news, ul.releases, ol.releases");

        if (lists.isEmpty()) {
            // 退而求其次：找主内容区里的列表
            lists = doc.select("main ul, main ol, .content ul, .content ol, .doc-content ul, .doc-content ol");
        }

        for (Element list : lists) {
            Elements items = list.select("> li");
            if (items.size() >= 2) {
                for (Element item : items) {
                    ChangelogEntry entry = extractEntry(item, baseUrl);
                    if (entry != null && isValidEntry(entry)) {
                        entries.add(entry);
                    }
                }
                if (!entries.isEmpty()) {
                    break;
                }
            }
        }

        return entries;
    }

    /**
     * 从单个元素中提取更新条目信息
     */
    private ChangelogEntry extractEntry(Element element, String baseUrl) {
        try {
            // 1. 提取标题（优先 h1-h4，其次 strong/b，最后取首段文本）
            String title = extractTitle(element);
            if (title == null || title.isBlank()) {
                return null;
            }

            // 2. 提取日期
            String date = extractDate(element.text());

            // 3. 提取内容（纯文本，排除标题）
            String content = extractContent(element, title);

            // 4. 提取链接
            String link = extractLink(element, baseUrl);

            // 5. 提取版本号（大模型优先），无版本时把标题中的日期当作版本标识
            String version = extractVersion(title);
            if (version == null) {
                version = extractDate(title);
            }

            ChangelogEntry entry = new ChangelogEntry();
            entry.setTitle(title);
            entry.setVersion(version);
            entry.setDate(date);
            entry.setContent(truncate(content, CONTENT_TRUNCATE));
            entry.setHtmlContent(element.html());
            entry.setLink(link);

            return entry;

        } catch (Exception e) {
            log.debug("Failed to extract entry: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从元素中提取标题
     */
    private String extractTitle(Element element) {
        // 优先 h1-h4
        Elements headings = element.select("h1, h2, h3, h4");
        if (!headings.isEmpty()) {
            return headings.first().text().trim();
        }

        // 其次 strong/b（如果在开头）
        Elements bolds = element.select("strong, b");
        if (!bolds.isEmpty()) {
            String text = bolds.first().text().trim();
            if (text.length() > 3 && text.length() < 200) {
                return text;
            }
        }

        // 最后取第一个非空文本节点
        String text = element.text().trim();
        if (text.length() > 3) {
            // 取第一行作为标题
            int newline = text.indexOf('\n');
            if (newline > 3 && newline < 200) {
                return text.substring(0, newline).trim();
            }
        }

        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /**
     * 从元素中提取正文内容（排除标题部分）
     */
    private String extractContent(Element element, String title) {
        // 克隆元素避免修改原始 DOM
        Element clone = element.clone();
        clone.select("h1, h2, h3, h4, h5, h6").remove();

        String text = clone.text().trim();

        // 如果内容以标题开头，移除标题
        if (text.startsWith(title)) {
            text = text.substring(title.length()).trim();
        }

        return text;
    }

    /**
     * 从元素中提取链接
     */
    private String extractLink(Element element, String baseUrl) {
        Elements links = element.select("a[href]");
        for (Element a : links) {
            String href = a.attr("abs:href");
            if (href != null && !href.isBlank()) {
                return href;
            }
        }
        return null;
    }

    /**
     * 从文本中提取版本号（大模型版本优先，通用版本次之）。
     * 如果前两者都不命中，退而求其次把日期也当作"版本标识"返回——
     * 对于日期型 changelog（如 Claude release notes）这是必要的。
     *
     * @return 提取到的版本号（或日期），如果没有则返回 null
     */
    public String extractVersion(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 优先匹配大模型版本号
        Matcher mm = MODEL_VERSION_PATTERN.matcher(text);
        if (mm.find()) {
            return mm.group(1);
        }

        // 退而求其次匹配通用版本号
        Matcher vm = GENERIC_VERSION_PATTERN.matcher(text);
        if (vm.find()) {
            return vm.group(1);
        }

        // 最后：日期也可以作为版本标识（日期型 changelog）
        String date = extractDate(text);
        if (date != null) {
            return date;
        }

        return null;
    }

    /**
     * 从文本中提取日期
     */
    private String extractDate(String text) {
        if (text == null) return null;

        // 常见日期格式：2024-01-15 / 2024/01/15 / Jan 15, 2024 / 2024年1月15日
        // 所有正则都必须包含捕获组 group(1)
        String[] patterns = {
                "\\b(20\\d{2}[-/][01]?\\d[-/][0123]?\\d)\\b",
                // 中文日期不能用 \\b（汉字不是 \\w），用前瞻/后顾避免嵌入数字
                "(?<![0-9])(20\\d{2}年[01]?\\d月[0123]?\\d日)(?![0-9])",
                "\\b((?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+20\\d{2})\\b",
                "\\b(\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+20\\d{2})\\b"
        };

        for (String p : patterns) {
            Matcher m = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) {
                return m.group(1);
            }
        }

        return null;
    }

    /**
     * 判断条目是否有效（至少有标题和内容）
     */
    private boolean isValidEntry(ChangelogEntry entry) {
        return entry != null
                && entry.getTitle() != null && !entry.getTitle().isBlank()
                && entry.getTitle().length() > 3
                && entry.getContent() != null && !entry.getContent().isBlank();
    }

    /**
     * 判断是否为页面级标题（而非更新条目标题）
     */
    private boolean isPageLevelTitle(String title) {
        if (title == null) return true;
        String lower = title.toLowerCase().trim();
        return lower.equals("更新日志")
                || lower.equals("changelog")
                || lower.equals("更新记录")
                || lower.equals("release notes")
                || lower.equals("what's new")
                || lower.equals("最新动态")
                || lower.equals("news")
                || lower.equals("公告")
                || lower.equals("announcements")
                || lower.equals("版本历史")
                || lower.equals("version history")
                // Claude/Gemini 页面常见页面级标题
                || lower.equals("claude platform")
                || lower.equals("gemini models")
                || lower.equals("all the latest");
    }

    /**
     * 判断 heading 是否为导航/页脚类（不是 changelog 条目）。
     * 用于过滤掉 "Solutions"、"Partners"、"Terms and policies" 等常见的 footer/sidebar heading。
     */
    private boolean isNavLikeHeading(String title) {
        if (title == null) return true;
        String lower = title.toLowerCase().trim();
        return lower.equals("solutions")
                || lower.equals("partners")
                || lower.equals("learn")
                || lower.equals("company")
                || lower.equals("help and security")
                || lower.equals("help")
                || lower.equals("terms and policies")
                || lower.equals("terms")
                || lower.equals("privacy")
                || lower.equals("careers")
                || lower.equals("press")
                || lower.equals("contact")
                || lower.equals("resources")
                || lower.equals("documentation")
                || lower.equals("community")
                || lower.equals("support")
                || lower.equals("about")
                || lower.equals("blog")
                || lower.equals("products")
                || lower.equals("platform")
                || lower.equals("developers")
                || lower.equals("api")
                || lower.equals("pricing")
                || lower.equals("sign in")
                || lower.equals("sign up")
                || lower.equals("home")
                || lower.equals("index");
    }

    /**
     * 判断元素是否为 heading 标签
     */
    private boolean isHeading(Element element) {
        String tag = element.tagName().toLowerCase();
        return tag.matches("h[1-6]");
    }

    /**
     * 截断文本到指定长度
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
