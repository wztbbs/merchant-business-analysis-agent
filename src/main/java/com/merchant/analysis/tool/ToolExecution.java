package com.merchant.analysis.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留实际解析参数、引用来源和结果，供模板及未来 ReAct 轨迹复用。 */
public record ToolExecution(String name, Map<String, Object> arguments, List<String> references, Map<String, Object> output) {
    public ToolExecution {
        arguments = freeze(arguments);
        references = List.copyOf(references);
        output = freeze(output);
    }
    public static Map<String, Object> snapshot(Map<String, Object> values) { return freeze(values); }

    @SuppressWarnings("unchecked")
    private static <T> T freeze(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, freeze(item)));
            return (T) Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(freeze(item)));
            return (T) Collections.unmodifiableList(result);
        }
        return value;
    }
}
