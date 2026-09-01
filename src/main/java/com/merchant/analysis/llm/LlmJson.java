package com.merchant.analysis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 解析 LLM 返回的 JSON：容忍 ```json 代码围栏与首尾杂文本 */
public final class LlmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {
    }

    public static JsonNode parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("LLM 返回为空");
        }
        String cleaned = text.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        try {
            return MAPPER.readTree(cleaned);
        } catch (Exception e) {
            // 兜底：截取第一个 JSON 值（对象或数组）再解析
            int start = firstIndexOf(cleaned, '{', '[');
            if (start < 0) {
                throw new IllegalArgumentException("LLM 返回中找不到 JSON: " + abbreviate(text), e);
            }
            char open = cleaned.charAt(start);
            char close = open == '{' ? '}' : ']';
            int end = cleaned.lastIndexOf(close);
            if (end <= start) {
                throw new IllegalArgumentException("LLM 返回 JSON 不完整: " + abbreviate(text), e);
            }
            try {
                return MAPPER.readTree(cleaned.substring(start, end + 1));
            } catch (Exception e2) {
                throw new IllegalArgumentException("LLM 返回 JSON 解析失败: " + abbreviate(text), e2);
            }
        }
    }

    private static int firstIndexOf(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
