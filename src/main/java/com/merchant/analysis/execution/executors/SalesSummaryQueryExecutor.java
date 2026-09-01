package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.DateRange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** sales_summary_query：销售额/订单量/客单价 当前期 vs 基线期 */
@Component("salesSummaryQueryExecutor")
public class SalesSummaryQueryExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public SalesSummaryQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sales_summary_query";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        double[] cur = summarize(ctx.current());
        double[] base = summarize(ctx.baseline());

        double curAov = cur[1] > 0 ? cur[0] / cur[1] : 0;
        double baseAov = base[1] > 0 ? base[0] / base[1] : 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current_total", round(cur[0]));
        out.put("baseline_total", round(base[0]));
        out.put("metric_delta", round(cur[0] - base[0]));
        out.put("order_delta", (long) cur[1] - (long) base[1]);
        out.put("aov_delta", round(curAov - baseAov));
        // 附加明细，供结论生成引用
        out.put("current_orders", (long) cur[1]);
        out.put("baseline_orders", (long) base[1]);
        out.put("current_aov", round(curAov));
        out.put("baseline_aov", round(baseAov));
        return out;
    }

    /** [销售额, 订单数] */
    private double[] summarize(DateRange range) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0), COUNT(DISTINCT order_id) FROM order_item WHERE ts >= ? AND ts < ?",
                (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)},
                range.startTs(), range.endTs());
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
