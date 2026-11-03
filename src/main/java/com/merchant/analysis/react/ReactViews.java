package com.merchant.analysis.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.tool.ResultReferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class ReactViews {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private ReactViews() {}
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("ReAct JSON 序列化失败", e); }
    }
    public static String fingerprint(Object value) {
        try {
            byte[] bytes = json(JSON.convertValue(value, Object.class)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Object reference(String path, AnalysisContext ctx) {
        return ResultReferences.bind(Map.of("value", Map.of("$ref", path)), ctx).arguments().get("value");
    }
    public static Object preview(Object value) { return preview(value, 0); }
    private static Object preview(Object value, int depth) {
        if (depth > 6) return "（请使用 inspect_result 查看更深字段）";
        if (value instanceof List<?> rows) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", rows.stream().limit(10).map(row -> preview(row, depth + 1)).toList());
            result.put("count", rows.size()); result.put("truncated", rows.size() > 10); return result;
        }
        if (value instanceof Map<?, ?> fields) {
            Map<String, Object> result = new LinkedHashMap<>();
            fields.entrySet().stream().limit(30).forEach(e -> result.put(e.getKey().toString(), preview(e.getValue(), depth + 1)));
            return result;
        }
        if (value instanceof String text && text.length() > 1000) return text.substring(0, 1000) + "…";
        return value;
    }
}
