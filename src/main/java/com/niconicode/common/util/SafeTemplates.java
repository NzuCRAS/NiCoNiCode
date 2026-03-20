package com.niconicode.common.util;

/**
 * 安全模板工具：用于拼接包含外部内容(用户输入/网页/Markdown/JSON)的提示词，避免 String.format/formatted
 * 因 '%' 字符触发 UnknownFormatConversionException。
 */
public final class SafeTemplates {

    private SafeTemplates() {
    }

    public static String s(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
