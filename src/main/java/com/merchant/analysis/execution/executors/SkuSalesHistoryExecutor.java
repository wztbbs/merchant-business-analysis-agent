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

/**
 * sku_sales_history：单 SKU 销售历史与变化量化。
 * sales_delta = 当前期销售额 − 基线期销售额（带符号，负值表示损失，方向中性）；
 * contribution_ratio 依赖上游输入 metric_delta（由 inputs 注入 params），缺省为 null。
 */
@Component("skuSalesHistoryExecutor")
public class SkuSalesHistoryExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public SkuSalesHistoryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sku_sales_history";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        String skuName = (String) params.get("sku");
        String skuId = skuIdOf(skuName);
        if (skuId == null) {
            out.put("sku_daily_avg_sales", null);
            out.put("sku_total_sales", null);
            out.put("sku_sales_share", null);
            out.put("sales_delta", null);
            out.put("contribution_ratio", null);
            return out;
        }

        double curTotal = salesOf(skuId, TimeParameters.current(params));
        double baseTotal = salesOf(skuId, TimeParameters.baseline(params));
        double overallCur = totalSales(TimeParameters.current(params));
        long days = TimeParameters.baseline(params).dayCount();

        double salesDelta = DecimalMath.difference(curTotal, baseTotal);   // 带符号：负值表示损失
        Object totalDelta = params.get("metric_delta");

        out.put("sku", skuName);
        out.put("sku_total_sales", round(curTotal));
        out.put("sku_baseline_sales", round(baseTotal));
        out.put("sku_daily_avg_sales", days > 0 ? round(DecimalMath.quotient(baseTotal, days)) : null); // 基线期日均，代表下架前正常水平
        out.put("sku_sales_share", overallCur > 0 ? round(DecimalMath.quotient(curTotal, overallCur)) : null);
        out.put("sales_delta", round(salesDelta));
        // 同号相比：该 SKU 与整体同向变化时贡献率为正，反向为负
        out.put("contribution_ratio",
                totalDelta != null && DecimalMath.decimal(totalDelta).signum() != 0
                        ? round(DecimalMath.divide(DecimalMath.decimal(salesDelta), DecimalMath.decimal(totalDelta)).doubleValue()) : null);
        return out;
    }

    private double salesOf(String skuId, DateRange range) {
        Double v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM order_item WHERE sku_id = ? AND ts >= ? AND ts < ?",
                Double.class, skuId, range.startTs(), range.endTs());
        return v;
    }

    private double totalSales(DateRange range) {
        Double v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM order_item WHERE ts >= ? AND ts < ?",
                Double.class, range.startTs(), range.endTs());
        return v;
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
        return Math.round(v * 10000) / 10000.0;
    }
}
