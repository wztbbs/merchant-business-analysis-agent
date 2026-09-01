package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** stockout_query：查询当前观察期内的缺货事件（stockout_start/stockout_end 配对展示为原始流水） */
@Component("stockoutQueryExecutor")
public class StockoutQueryExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public StockoutQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "stockout_query";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        List<Map<String, Object>> events = new ArrayList<>();
        jdbc.query("SELECT l.sku_id, s.name, l.event_type, l.event_time FROM sku_status_log l "
                        + "JOIN sku s ON s.sku_id = l.sku_id "
                        + "WHERE l.event_type LIKE 'stockout%' AND l.event_time >= ? AND l.event_time < ? "
                        + "ORDER BY l.event_time",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sku_id", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("event_type", rs.getString(3));
                    m.put("event_time", rs.getString(4));
                    events.add(m);
                },
                ctx.current().startTs(), ctx.current().endTs());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stockout_events", events);
        out.put("comment", events.isEmpty() ? "观察期内无缺货事件，缺货因素可排除。" : "观察期内发现 " + events.size() + " 条缺货事件。");
        return out;
    }
}
