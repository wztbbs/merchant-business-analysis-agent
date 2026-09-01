package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.DateRange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** category_sales_breakdown：品类维度销售拆解 */
@Component("categorySalesBreakdownExecutor")
public class CategorySalesBreakdownExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public CategorySalesBreakdownExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "category_sales_breakdown";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        Map<String, Double> cur = salesByCategory(ctx.current());
        Map<String, Double> base = salesByCategory(ctx.baseline());

        Set<String> categories = new HashSet<>(cur.keySet());
        categories.addAll(base.keySet());
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (String category : categories) {
            double curTotal = cur.getOrDefault(category, 0.0);
            double baseTotal = base.getOrDefault(category, 0.0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category);
            item.put("current_total", round(curTotal));
            item.put("baseline_total", round(baseTotal));
            item.put("delta", round(curTotal - baseTotal));
            breakdown.add(item);
        }
        // 按 |delta| 降序：变化最大的品类在前，方向由 delta 符号表达（与 sku_sales_breakdown 口径一致）
        breakdown.sort(Comparator.comparingDouble(m -> -Math.abs(((Number) m.get("delta")).doubleValue())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category_breakdown", breakdown);
        return out;
    }

    private Map<String, Double> salesByCategory(DateRange range) {
        Map<String, Double> result = new HashMap<>();
        jdbc.query("SELECT s.category, SUM(o.amount) FROM order_item o JOIN sku s ON s.sku_id = o.sku_id "
                        + "WHERE o.ts >= ? AND o.ts < ? GROUP BY s.category",
                rs -> {
                    result.put(rs.getString(1), rs.getDouble(2));
                },
                range.startTs(), range.endTs());
        return result;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
