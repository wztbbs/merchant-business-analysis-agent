package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.time.TimeParameters;
import com.merchant.analysis.execution.CapabilityExecutor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sku_status_query：查询 SKU 观察期末的在架状态，以及两期覆盖窗口内最近一次下架时间。
 * 查不到下架事件时 delisting_time 为 null —— 执行控制器据此判定用户假设不成立。
 */
@Component("skuStatusQueryExecutor")
public class SkuStatusQueryExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public SkuStatusQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "sku_status_query";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        String skuName = (String) params.get("sku");
        String skuId = skuIdOf(skuName);
        out.put("sku", skuName);
        out.put("sku_id", skuId);
        if (skuId == null) {
            out.put("delisting_time", null);
            out.put("listing_status", "unknown");
            return out;
        }

        var current = TimeParameters.current(params);
        var baseline = TimeParameters.baseline(params);
        String windowStart = current.startTs().compareTo(baseline.startTs()) < 0 ? current.startTs() : baseline.startTs();
        String windowEnd = current.endTs().compareTo(baseline.endTs()) > 0 ? current.endTs() : baseline.endTs();
        // 两期覆盖窗口内的最近一次下架事件
        String delistingTime = queryOneOrNull(
                "SELECT event_time FROM sku_status_log WHERE sku_id = ? AND event_type = 'delisting' "
                        + "AND event_time >= ? AND event_time < ? ORDER BY event_time DESC LIMIT 1",
                skuId, windowStart, windowEnd);
        // 观察期之后的记录不能改变历史期末状态。
        String lastEvent = queryOneOrNull(
                "SELECT event_type FROM sku_status_log WHERE sku_id = ? "
                        + "AND event_type IN ('listing','delisting') AND event_time < ? ORDER BY event_time DESC LIMIT 1",
                skuId, current.endTs());

        out.put("delisting_time", delistingTime);
        out.put("listing_status", "delisting".equals(lastEvent) ? "已下架" : "在架");
        return out;
    }

    private String skuIdOf(String skuName) {
        if (skuName == null || skuName.isBlank()) {
            return null;
        }
        return queryOneOrNull("SELECT sku_id FROM sku WHERE name = ?", skuName);
    }

    private String queryOneOrNull(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, String.class, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
