package com.merchant.analysis.calculation;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 操作数已由运行时绑定；算子仅做确定性计算，不查询数据、不执行表达式。 */
@Component
public class CalculationEngine {
    public static final Set<String> OPERATORS = Set.of("sum", "avg", "min", "max", "compare", "difference", "ratio", "relative_change");

    public Map<String, Object> execute(String operator, Map<String, Object> arguments) {
        if (!OPERATORS.contains(operator)) throw new IllegalArgumentException("未知算子: " + operator);
        if (arguments.size() != 1 || !(arguments.get("input") instanceof Map<?, ?> input))
            throw new IllegalArgumentException("算子参数必须仅包含 input 对象");
        if (Set.of("sum", "avg", "min", "max").contains(operator)) return series(operator, input);
        boolean change = operator.equals("relative_change");
        requireFields(input, change ? Set.of("current", "baseline") : Set.of("left", "right"));
        BigDecimal left = DecimalMath.decimal(input.get(change ? "current" : "left"));
        BigDecimal right = DecimalMath.decimal(input.get(change ? "baseline" : "right"));
        Map<String, Object> out = new LinkedHashMap<>();
        switch (operator) {
            case "compare" -> {
                int relation = Integer.signum(left.compareTo(right));
                out.put("value", Integer.toString(relation));
                out.put("relation", relation > 0 ? "greater" : relation < 0 ? "less" : "equal");
            }
            case "difference" -> out.put("value", DecimalMath.plain(DecimalMath.subtract(left, right)));
            default -> {
                if (right.signum() <= 0) throw new IllegalArgumentException("ratio/relative_change 的基准必须大于零");
                BigDecimal value = DecimalMath.divide(change ? DecimalMath.subtract(left, right) : left, right);
                out.put("value", DecimalMath.plain(value));
                out.put("percent", DecimalMath.plain(value.multiply(BigDecimal.valueOf(100))));
            }
        }
        return out;
    }

    private Map<String, Object> series(String operator, Map<?, ?> input) {
        Object kind = input.get("kind");
        boolean field = "field".equals(kind);
        if (!field && !"values".equals(kind)) throw new IllegalArgumentException("kind 必须为 values 或 field");
        requireFields(input, field ? Set.of("kind", "collection", "field") : Set.of("kind", "values"));
        Object collection = input.get(field ? "collection" : "values");
        if (!(collection instanceof List<?> rows) || rows.isEmpty() || rows.size() > 10000)
            throw new IllegalArgumentException("集合必须包含1到10000个元素");
        String key = null;
        if (field) {
            if (!(input.get("field") instanceof String text) || text.isBlank() || text.contains("."))
                throw new IllegalArgumentException("field 必须为非空直接字段名，不能为嵌套路径");
            key = (String) input.get("field");
        }
        List<BigDecimal> values = new ArrayList<>();
        for (Object row : rows) {
            Object value = row;
            if (field) {
                if (!(row instanceof Map<?, ?> map) || !map.containsKey(key))
                    throw new IllegalArgumentException("集合中有对象缺少字段: " + key);
                value = map.get(key);
            }
            // 不跳过缺失、null 或非法值，避免悄悄改变参与集合。
            values.add(DecimalMath.decimal(value));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", values.size());
        if (operator.equals("sum") || operator.equals("avg")) {
            out.put("value", DecimalMath.plain(operator.equals("sum") ? DecimalMath.sum(values) : DecimalMath.average(values)));
            out.put("method", operator.equals("sum") ? "arithmetic_sum" : "equal_weight_arithmetic_mean");
        } else {
            int index = 0;
            for (int i = 1; i < values.size(); i++) {
                int comparison = values.get(i).compareTo(values.get(index));
                if (operator.equals("min") ? comparison < 0 : comparison > 0) index = i;
            }
            out.put("value", DecimalMath.plain(values.get(index)));
            out.put("index", index);
            if (field) out.put("item", rows.get(index));
        }
        return out;
    }

    private static void requireFields(Map<?, ?> input, Set<String> expected) {
        if (!input.keySet().equals(expected)) throw new IllegalArgumentException("input 字段必须为 " + expected);
    }
}
