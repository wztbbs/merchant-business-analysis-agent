package com.merchant.analysis.tool;

import com.merchant.analysis.execution.AnalysisContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** 显式引用形如 {"$ref":"S1.top_contributors.0.sku_id"}，不执行字符串表达式。 */
public final class ResultReferences {
    private static final Pattern PATH = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.(?:[A-Za-z_][A-Za-z0-9_]*|[0-9]+))+");
    public record Binding(Map<String, Object> arguments, List<String> references) {}
    private ResultReferences() {}

    @SuppressWarnings("unchecked")
    public static Binding bind(Map<String, Object> arguments, AnalysisContext context) {
        if (arguments == null) throw new IllegalArgumentException("工具参数必须为对象");
        Set<String> references = new LinkedHashSet<>();
        Map<String, Object> bound = (Map<String, Object>) visit(arguments, context, references, 0);
        return new Binding(bound, List.copyOf(references));
    }
    private static Object visit(Object value, AnalysisContext context, Set<String> references, int depth) {
        if (depth > 32) throw new IllegalArgumentException("工具参数嵌套过深");
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$ref")) {
                if (map.size() != 1 || !(map.get("$ref") instanceof String path) || !PATH.matcher(path).matches())
                    throw new IllegalArgumentException("结果引用必须是完整 $ref 对象");
                if (context == null) throw new IllegalArgumentException("没有可引用的调用结果");
                String[] parts = path.split("\\.");
                Object result = context.stepResults().get(parts[0]);
                if (result == null) throw new IllegalArgumentException("引用的成功调用不存在: " + path);
                for (int i = 1; i < parts.length; i++) {
                    if (result instanceof Map<?, ?> fields && fields.containsKey(parts[i])) result = fields.get(parts[i]);
                    else if (result instanceof List<?> rows && parts[i].matches("[0-9]+")) {
                        int index;
                        try { index = Integer.parseInt(parts[i]); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("引用下标越界: " + path); }
                        if (index >= rows.size()) throw new IllegalArgumentException("引用下标越界: " + path);
                        result = rows.get(index);
                    } else throw new IllegalArgumentException("引用字段不存在: " + path);
                }
                references.add(path);
                return result;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("参数键必须为字符串");
                result.put(text, visit(child, context, references, depth + 1));
            });
            return result;
        }
        if (value instanceof List<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object child : values) result.add(visit(child, context, references, depth + 1));
            return result;
        }
        return value;
    }
}
