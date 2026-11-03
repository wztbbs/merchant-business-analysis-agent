package com.merchant.analysis.tool;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.execution.executors.*;
import com.merchant.analysis.time.*;
import com.merchant.analysis.understanding.QueryUnderstanding;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessToolsIntegrationTest {
    @Test void allBusinessToolsExecuteThroughContractsAndPriceScopeIsExplicit() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/tables.sql")).execute(ds);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update("INSERT INTO sku VALUES('s1','可乐','饮料'),('s2','橙汁','饮料')");
            jdbc.update("INSERT INTO order_item(order_id,sku_id,ts,qty,amount,price,activity_id) VALUES "
                    + "('b1','s1','2026-09-15 12:00:00',10,100,10,'a1'),"
                    + "('b2','s2','2026-09-15 12:00:00',10,50,5,'a1'),"
                    + "('c1','s1','2026-10-15 12:00:00',5,60,12,NULL),"
                    + "('c2','s2','2026-10-15 12:00:00',20,100,5,NULL)");
            jdbc.update("INSERT INTO activity VALUES('a1','满减','2026-09-01 00:00:00','2026-09-30 23:59:59','满50减5')");
            jdbc.update("INSERT INTO shop_traffic_daily VALUES('2026-09-15',1000,100),('2026-10-15',900,90)");
            jdbc.update("INSERT INTO sku_status_log(sku_id,event_type,event_time) VALUES "
                    + "('s1','listing','2026-09-01 00:00:00'),('s1','stockout_start','2026-10-05 00:00:00'),"
                    + "('s1','stockout_end','2026-10-06 00:00:00'),('s1','delisting','2026-10-20 00:00:00')");
            CapabilityCatalog catalog = new CapabilityCatalog();
            Map<String, CapabilityExecutor> executors = Map.of(
                    "salesSummaryQueryExecutor", new SalesSummaryQueryExecutor(jdbc),
                    "skuSalesBreakdownExecutor", new SkuSalesBreakdownExecutor(jdbc),
                    "categorySalesBreakdownExecutor", new CategorySalesBreakdownExecutor(jdbc),
                    "skuStatusQueryExecutor", new SkuStatusQueryExecutor(jdbc),
                    "skuSalesHistoryExecutor", new SkuSalesHistoryExecutor(jdbc),
                    "substituteSkuAnalysisExecutor", new SubstituteSkuAnalysisExecutor(jdbc),
                    "campaignQueryExecutor", new CampaignQueryExecutor(jdbc),
                    "trafficConversionQueryExecutor", new TrafficConversionQueryExecutor(jdbc),
                    "priceChangeQueryExecutor", new PriceChangeQueryExecutor(jdbc),
                    "stockoutQueryExecutor", new StockoutQueryExecutor(jdbc));
            TimeResolver resolver = new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
            var time = resolver.resolvePeriods(TimeSpec.calendar("month", -1), null, resolver.capture());
            QueryUnderstanding u = new QueryUnderstanding("查询", "sales_amount", TimeSpec.calendar("month", -1), null,
                    null, null, null, null, null, null).withResolvedTime(time);
            AnalysisContext ctx = new AnalysisContext(u, time.current(), time.baseline());
            ToolRuntime runtime = new ToolRuntime(new ToolCatalog(catalog), catalog, executors, new CalculationEngine(), resolver);
            ToolSession session = runtime.newSession(ctx);
            session.loadTools(catalog.all().stream().map(CapabilityCatalog.CapabilityDef::name).toList());
            session.execute("summary", "sales_summary_query", Map.of());
            assertEquals(10.0, ctx.lookup("summary", "metric_delta"));
            Map<String, Object> shared = Map.of("sku", "可乐", "metric_delta", Map.of("$ref", "summary.metric_delta"));
            for (var tool : catalog.all()) {
                if (!tool.kind().equals("business") || tool.name().equals("sales_summary_query")) continue;
                var output = session.execute("q_" + tool.name(), tool.name(), runtime.selectArguments(tool.name(), shared));
                if (tool.name().equals("sku_sales_history")) assertEquals(-4.0, output.get("contribution_ratio"));
            }
            session.execute("computed_delta", "difference", Map.of("input", Map.of(
                    "left", Map.of("$ref", "summary.current_total"), "right", Map.of("$ref", "summary.baseline_total"))));
            var derived = session.execute("sku_from_calculation", "sku_sales_history",
                    Map.of("sku", "可乐", "metric_delta", Map.of("$ref", "computed_delta.value")));
            assertEquals(-4.0, derived.get("contribution_ratio"));
            // 历史里有无关的商品集合时，全店扫描仍必须按本次参数执行。
            ctx.putStepResult("unrelated", Map.of("top_contributors", List.of(Map.of("sku_id", "s2"))));
            var all = session.execute("price_all", "price_change_query", Map.of());
            assertEquals("s1", ((Map<?, ?>) ((List<?>) all.get("price_changes")).get(0)).get("sku_id"));
            var empty = session.execute("price_empty", "price_change_query", Map.of("sku_ids", List.of()));
            assertTrue(((List<?>) empty.get("price_changes")).isEmpty());
            var other = session.execute("price_other", "price_change_query", Map.of("sku_ids", List.of("s2")));
            assertTrue(((List<?>) other.get("price_changes")).isEmpty());
        } finally { ds.destroy(); }
    }
}
