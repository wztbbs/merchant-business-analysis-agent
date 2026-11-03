package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.calculation.DecimalMath;
import com.merchant.analysis.time.TimeParameters;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.DateRange;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** substitute_sku_analysis：同品类其他 SKU 销量变化，评估下架后的替代承接效应 */
@Component("substituteSkuAnalysisExecutor")
public class SubstituteSkuAnalysisExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public SubstituteSkuAnalysisExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "substitute_sku_analysis";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        String skuName = (String) params.get("sku");
        String skuId = skuIdOf(skuName);
        if (skuId == null) {
            out.put("substitution_effect", null);
            return out;
        }

        String category = jdbc.queryForObject("SELECT category FROM sku WHERE sku_id = ?", String.class, skuId);
        double curOthers = othersSales(skuId, TimeParameters.current(params));
        double baseOthers = othersSales(skuId, TimeParameters.baseline(params));
        double othersDelta = DecimalMath.difference(curOthers, baseOthers);

        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("category", category);
        effect.put("others_current_total", round(curOthers));
        effect.put("others_baseline_total", round(baseOthers));
        effect.put("others_delta", round(othersDelta));
        effect.put("comment", othersDelta > 0
                ? "同品类其他商品总体销售上涨；仅此不能证明需求转移"
                : "同品类其他商品总体销售未增长");
        out.put("substitution_effect", effect);
        return out;
    }

    private double othersSales(String skuId, DateRange range) {
        Double v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(o.amount),0) FROM order_item o JOIN sku s ON s.sku_id = o.sku_id "
                        + "WHERE s.category = (SELECT category FROM sku WHERE sku_id = ?) "
                        + "AND o.sku_id <> ? AND o.ts >= ? AND o.ts < ?",
                Double.class, skuId, skuId, range.startTs(), range.endTs());
        return v == null ? 0 : v;
    }

    private String skuIdOf(String skuName) {
        if (skuName == null || skuName.isBlank()) {
            return null;
        }
        try {
            return jdbc.queryForObject("SELECT sku_id FROM sku WHERE name = ?", String.class, skuName);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
