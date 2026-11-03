package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.calculation.DecimalMath;
import com.merchant.analysis.time.TimeParameters;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.DateRange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** traffic_conversion_query：曝光/进店UV/转化率 当前期 vs 基线期 */
@Component("trafficConversionQueryExecutor")
public class TrafficConversionQueryExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public TrafficConversionQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "traffic_conversion_query";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        double[] cur = traffic(TimeParameters.current(params));
        double[] base = traffic(TimeParameters.baseline(params));
        long curOrders = orders(TimeParameters.current(params));
        long baseOrders = orders(TimeParameters.baseline(params));

        double curConversion = cur[1] > 0 ? DecimalMath.quotient(curOrders, cur[1]) : 0;
        double baseConversion = base[1] > 0 ? DecimalMath.quotient(baseOrders, base[1]) : 0;

        Map<String, Object> trafficDelta = new LinkedHashMap<>();
        trafficDelta.put("current_exposure", (long) cur[0]);
        trafficDelta.put("baseline_exposure", (long) base[0]);
        trafficDelta.put("exposure_delta", (long) cur[0] - (long) base[0]);
        trafficDelta.put("current_visit_uv", (long) cur[1]);
        trafficDelta.put("baseline_visit_uv", (long) base[1]);
        trafficDelta.put("visit_uv_delta", (long) cur[1] - (long) base[1]);

        Map<String, Object> conversionDelta = new LinkedHashMap<>();
        conversionDelta.put("current_conversion", round(curConversion));
        conversionDelta.put("baseline_conversion", round(baseConversion));
        conversionDelta.put("conversion_delta", round(DecimalMath.difference(curConversion, baseConversion)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traffic_delta", trafficDelta);
        out.put("conversion_delta", conversionDelta);
        return out;
    }

    /** [曝光, 进店UV] */
    private double[] traffic(DateRange range) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(exposure),0), COALESCE(SUM(visit_uv),0) FROM shop_traffic_daily "
                        + "WHERE stat_date >= ? AND stat_date < ?",
                (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)},
                range.start(), range.endExclusive());
    }

    private long orders(DateRange range) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT order_id) FROM order_item WHERE ts >= ? AND ts < ?",
                Long.class, range.startTs(), range.endTs());
        return v == null ? 0 : v;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
