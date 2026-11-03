package com.merchant.analysis.understanding;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.tool.ResultReferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 模型看到有界摘要，引用始终绑定服务端原结果，不用摘要冒充完整集合。 */
public final class HistoryFacts {
    public record Candidate(String path, String shape, Object preview, Integer count, boolean preview_truncated) {}
    private HistoryFacts() {}
    public static List<Candidate> summaries(ChainContext chain) {
        List<Candidate> result = new ArrayList<>();
        chain.stepResults().forEach((step, fields) -> fields.forEach((key, value) -> {
            if (result.size() >= 40 || key.startsWith("_")) return;
            if (value instanceof List<?> rows) result.add(new Candidate(step + "." + key, "collection", preview(rows, 0), rows.size(), truncated(value, 0)));
            else result.add(new Candidate(step + "." + key, value instanceof Map ? "object" : "scalar", preview(value, 0), null, truncated(value, 0)));
        }));
        return List.copyOf(result);
    }
    private static boolean truncated(Object value, int depth) {
        if (depth > 3) return true;
        if (value instanceof List<?> rows) return rows.size() > 3 || rows.stream().anyMatch(row -> truncated(row, depth + 1));
        if (value instanceof Map<?, ?> fields) return fields.size() > 12 || fields.values().stream().anyMatch(child -> truncated(child, depth + 1));
        return value instanceof String text && text.length() > 200;
    }
    private static Object preview(Object value, int depth) {
        if (depth > 3) return "（更深层数据请引用原结果）";
        if (value instanceof List<?> rows) return rows.stream().limit(3).map(row -> preview(row, depth + 1)).toList();
        if (value instanceof Map<?, ?> fields) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            fields.entrySet().stream().limit(12).forEach(e -> result.put((String) e.getKey(), preview(e.getValue(), depth + 1)));
            return result;
        }
        if (value instanceof String text && text.length() > 200) return text.substring(0, 200) + "…";
        return value;
    }
    public static void validate(QueryTask.FactReference reference, List<ChainContext> chains) {
        ChainContext chain = chains.stream().filter(c -> c.runId().equals(reference.run_id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("引用不属于候选历史分析"));
        AnalysisContext context = new AnalysisContext(null, null, null);
        chain.stepResults().forEach(context::putStepResult);
        ResultReferences.bind(Map.of("value", Map.of("$ref", reference.path())), context);
    }
}
