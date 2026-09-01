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

/** sku_sales_breakdown：SKU 维度贡献拆解，产出变化贡献最大的单品榜单（前 10，涨跌都含） */
@Component("skuSalesBreakdownExecutor")
public class SkuSalesBreakdownExecutor implements CapabilityExecutor {

    private static final int TOP_N = 10;

    private final JdbcTemplate jdbc;

    public SkuSalesBreakdownExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sku_sales_breakdown";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        Map<String, Double> cur = salesBySku(ctx.current());
        Map<String, Double> base = salesBySku(ctx.baseline());
        Map<String, String[]> skuInfo = skuInfo();

        List<Map<String, Object>> contributors = new ArrayList<>();
        // 并集：当前期或基线期有销售的 SKU 都参与比较（覆盖整期下架的情况）
        Set<String> skuIds = new HashSet<>(cur.keySet());
        skuIds.addAll(base.keySet());
        for (String skuId : skuIds) {
            double curTotal = cur.getOrDefault(skuId, 0.0);
            double baseTotal = base.getOrDefault(skuId, 0.0);
            double delta = curTotal - baseTotal;
            if (delta != 0) {   // 涨跌都纳入，场景对「上升/下滑」方向中性
                String[] info = skuInfo.getOrDefault(skuId, new String[]{skuId, null});
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sku_id", skuId);
                item.put("name", info[0]);
                item.put("category", info[1]);
                item.put("current_total", round(curTotal));
                item.put("baseline_total", round(baseTotal));
                item.put("delta", round(delta));
                contributors.add(item);
            }
        }
        // 按 |delta| 降序：变化贡献最大者在前，方向由 delta 符号表达
        contributors.sort(Comparator.comparingDouble(
                m -> -Math.abs(((Number) m.get("delta")).doubleValue())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("top_contributors", contributors.stream().limit(TOP_N).toList());
        return out;
    }

    private Map<String, Double> salesBySku(DateRange range) {
        Map<String, Double> result = new HashMap<>();
        jdbc.query("SELECT sku_id, SUM(amount) FROM order_item WHERE ts >= ? AND ts < ? GROUP BY sku_id",
                rs -> {
                    result.put(rs.getString(1), rs.getDouble(2));
                },
                range.startTs(), range.endTs());
        return result;
    }

    /** sku_id → [name, category] */
    private Map<String, String[]> skuInfo() {
        Map<String, String[]> result = new HashMap<>();
        jdbc.query("SELECT sku_id, name, category FROM sku",
                rs -> {
                    result.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
                });
        return result;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
