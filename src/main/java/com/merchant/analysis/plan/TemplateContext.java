package com.merchant.analysis.plan;

import com.merchant.analysis.time.TimeSpec;
import com.merchant.analysis.understanding.QueryUnderstanding;
import java.util.HashMap;
import java.util.Map;

/** 保留结构化时间参数；完整占位符替换不能退化成字符串插值。 */
class TemplateContext {
    private final Map<String, Object> variables;
    private TemplateContext(Map<String, Object> variables) { this.variables = variables; }
    static TemplateContext from(QueryUnderstanding understanding) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("entity.sku", understanding.skuOrNull() == null ? "" : understanding.skuOrNull());
        variables.put("time_range", understanding.time_range() == null
                ? TimeSpec.calendar("month", -1) : understanding.time_range());
        variables.put("comparison_baseline", understanding.comparison_baseline());
        return new TemplateContext(variables);
    }
    Object render(Object value) {
        if (!(value instanceof String text)) return value;
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            if (text.equals("{{" + variable.getKey() + "}}")) return variable.getValue();
        }
        String rendered = text;
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            String placeholder = "{{" + variable.getKey() + "}}";
            if (rendered.contains(placeholder)) {
                if (!(variable.getValue() instanceof String replacement))
                    throw new IllegalArgumentException("结构化参数必须使用完整占位符: " + placeholder);
                rendered = rendered.replace(placeholder, replacement);
            }
        }
        return rendered;
    }
}
