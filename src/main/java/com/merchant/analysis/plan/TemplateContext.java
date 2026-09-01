package com.merchant.analysis.plan;

import com.merchant.analysis.understanding.QueryUnderstanding;

import java.util.HashMap;
import java.util.Map;

/**
 * 模板变量上下文：把 QueryUnderstanding 归一化为 {{xxx}} 变量表（业务默认值在此补齐），
 * 编织阶段只做查表替换，不关心默认值等归一化逻辑。
 */
class TemplateContext {

    private final Map<String, String> variables;

    private TemplateContext(Map<String, String> variables) {
        this.variables = variables;
    }

    static TemplateContext from(QueryUnderstanding understanding) {
        Map<String, String> variables = new HashMap<>();
        variables.put("entity.sku", understanding.skuOrNull() == null ? "" : understanding.skuOrNull());
        variables.put("time_range",
                understanding.time_range() == null ? "last_month" : understanding.time_range());
        variables.put("comparison_baseline",
                understanding.comparison_baseline() == null ? "previous_month" : understanding.comparison_baseline());
        return new TemplateContext(variables);
    }

    /** {{key}} 占位符替换；非字符串参数原样透传 */
    Object render(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String rendered = text;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            rendered = rendered.replace("{{" + variable.getKey() + "}}", variable.getValue());
        }
        return rendered;
    }
}
