package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.time.DateRange;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ExecutorTimeTest {
    @Test void sqlUsesExplicitRangesAndBaselineDaysRatherThanContextOrCurrentMonthDays() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE sku(sku_id TEXT, name TEXT)");
            jdbc.execute("CREATE TABLE order_item(sku_id TEXT, order_id TEXT, amount REAL, ts TEXT)");
            jdbc.execute("CREATE TABLE sku_status_log(sku_id TEXT, event_type TEXT, event_time TEXT)");
            jdbc.update("INSERT INTO sku VALUES('s1','可乐')");
            jdbc.update("INSERT INTO order_item VALUES('s1','b',300,'2026-09-30 23:59:59')");
            jdbc.update("INSERT INTO order_item VALUES('s1','a',100,'2026-10-01 00:00:00')");
            jdbc.update("INSERT INTO order_item VALUES('s1','c',200,'2026-10-31 23:59:59')");
            jdbc.update("INSERT INTO order_item VALUES('s1','d',999,'2026-11-01 00:00:00')");
            jdbc.update("INSERT INTO sku_status_log VALUES('s1','delisting','2026-10-31 23:59:59')");
            jdbc.update("INSERT INTO sku_status_log VALUES('s1','listing','2026-11-01 00:00:00')");
            Map<String, Object> params = Map.of("sku", "可乐", "time_range", new DateRange("2026-10-01", "2026-11-01"),
                    "baseline", new DateRange("2026-09-01", "2026-10-01"));
            AnalysisContext unrelated = new AnalysisContext(null, new DateRange("2000-01-01", "2000-02-01"),
                    new DateRange("1999-12-01", "2000-01-01"));
            var summary = new SalesSummaryQueryExecutor(jdbc).execute(params, unrelated);
            assertEquals(300.0, summary.get("current_total"));
            assertEquals(300.0, summary.get("baseline_total"));
            assertEquals(2L, summary.get("current_orders"));
            var sku = new SkuSalesHistoryExecutor(jdbc).execute(params, unrelated);
            assertEquals(10.0, sku.get("sku_daily_avg_sales"));
            assertEquals(300.0, sku.get("sku_total_sales"));
            var status = new SkuStatusQueryExecutor(jdbc).execute(params, unrelated);
            assertEquals("已下架", status.get("listing_status"));
            assertEquals("2026-10-31 23:59:59", status.get("delisting_time"));
            assertThrows(IllegalArgumentException.class, () -> new SalesSummaryQueryExecutor(jdbc).execute(Map.of(), unrelated));
        } finally { ds.destroy(); }
    }
}
