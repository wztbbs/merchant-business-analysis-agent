package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityExecutor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sku_status_query：查询 SKU 当前在架状态，以及观察窗（基线期起点 ~ 当前期终点）内最近一次下架时间。
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

        // 观察窗内的最近一次下架事件
        String delistingTime = queryOneOrNull(
                "SELECT event_time FROM sku_status_log WHERE sku_id = ? AND event_type = 'delisting' "
                        + "AND event_time >= ? AND event_time < ? ORDER BY event_time DESC LIMIT 1",
                skuId, ctx.baseline().startTs(), ctx.current().endTs());
        // 当前在架状态：以最近一次上下架事件为准
        String lastEvent = queryOneOrNull(
                "SELECT event_type FROM sku_status_log WHERE sku_id = ? "
                        + "AND event_type IN ('listing','delisting') ORDER BY event_time DESC LIMIT 1",
                skuId);

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
