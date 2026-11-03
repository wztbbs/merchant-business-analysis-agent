package com.merchant.analysis.tool;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.time.*;
import com.merchant.analysis.understanding.QueryUnderstanding;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ToolRuntimeTest {
    private final CapabilityCatalog capabilities = new CapabilityCatalog();
    private final ToolCatalog catalog = new ToolCatalog(capabilities);
    private final TimeResolver resolver = new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
    private ToolRuntime runtime(Map<String, CapabilityExecutor> executors) {
        return new ToolRuntime(catalog, capabilities, executors, new CalculationEngine(), resolver);
    }
    private AnalysisContext context() {
        var time = resolver.resolvePeriods(TimeSpec.calendar("month", -1), null, resolver.capture());
        var u = new QueryUnderstanding("查询", "sales_amount", TimeSpec.calendar("month", -1), null, null, null, null, null, null, null).withResolvedTime(time);
        return new AnalysisContext(u, time.current(), time.baseline());
    }
    private static Map<String, Object> input(Map<String, Object> value) { return Map.of("input", value); }
    private static Map<String, Object> ref(String path) { return Map.of("$ref", path); }

    @Test void toolsMustBeLoadedPerSessionAndChainedResultsUseReferences() {
        ToolRuntime runtime = runtime(Map.of());
        ToolSession session = runtime.newSession(context());
        assertEquals(18, session.summaries().size());
        assertTrue(session.loadedNames().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> session.loadTools(List.of("sum", "unknown_tool")));
        assertTrue(session.loadedNames().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> session.execute("c1", "sum", input(Map.of("kind", "values", "values", List.of(1, 2)))));
        session.loadTools(List.of("sum", "ratio", "max"));
        assertEquals(3, session.loadedDefinitions().size());
        session.execute("c1", "sum", input(Map.of("kind", "values", "values", List.of("0.1", "0.2"))));
        var result = session.execute("c2", "ratio", input(Map.of("left", ref("c1.value"), "right", "0.1")));
        assertEquals("3", result.get("value"));
        assertEquals(List.of("c1.value"), session.history().get(1).execution().references());
        assertEquals("0.3", ((Map<?, ?>) session.history().get(1).execution().arguments().get("input")).get("left"));
        assertThrows(IllegalArgumentException.class, () -> session.execute("c1", "sum", input(Map.of("kind", "values", "values", List.of(1)))));
        assertThrows(IllegalArgumentException.class, () -> runtime.newSession(context()).execute("c3", "ratio", input(Map.of("left", 1, "right", 1))));
        assertThrows(UnsupportedOperationException.class, () -> result.put("value", "fake"));
    }
    @Test void fullCollectionAndIndexedObjectReferencesAreBoundWithoutModelCopying() {
        AnalysisContext ctx = context();
        ctx.putStepResult("q1", Map.of("rows", List.of(Map.of("sku_id", "a", "delta", -10), Map.of("sku_id", "b", "delta", -2))));
        ToolSession session = runtime(Map.of()).newSession(ctx);
        session.loadTools(List.of("min", "difference"));
        var extreme = session.execute("c1", "min", input(Map.of("kind", "field", "collection", ref("q1.rows"), "field", "delta")));
        assertEquals("a", ((Map<?, ?>) extreme.get("item")).get("sku_id"));
        var diff = session.execute("c2", "difference", input(Map.of("left", ref("q1.rows.1.delta"), "right", ref("c1.value"))));
        assertEquals("8", diff.get("value"));
        assertThrows(IllegalArgumentException.class, () -> session.execute("bad", "difference", input(Map.of("left", ref("q1.rows.99.delta"), "right", 1))));
        assertFalse(ctx.stepResults().containsKey("bad"));
    }
    @Test void rejectsUnknownMissingReferencesAndMalformedSchemas() {
        var runtime = runtime(Map.of());
        var ctx = context();
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("sum", input(Map.of("kind", "values", "values", ref("missing.rows"))), ctx));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("difference", input(Map.of("left", ref("q1.value+1"), "right", 1)), ctx));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("sum", Map.of("input", Map.of("kind", "values", "values", List.of(1)), "extra", 1), ctx));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("ratio", input(Map.of("left", 1, "right", 2, "extra", 3)), ctx));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("ratio", input(Map.of("left", true, "right", 2)), ctx));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("avg", input(Map.of("kind", "values", "values", List.of())), ctx));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThrows(IllegalArgumentException.class, () -> SchemaValidator.validate(
                mapper.valueToTree(Double.NaN), mapper.valueToTree(Map.of("type", "number")), "output"));
    }

    @Test void changedObservationPeriodDerivesItsOwnComparisonPeriod() {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        CapabilityExecutor executor = new CapabilityExecutor() {
            public String name() { return "sku_sales_history"; }
            public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
                received.set(params);
                return Map.of("sku_total_sales", 0, "sku_daily_avg_sales", 0, "sku_sales_share", 0,
                        "sales_delta", 0, "contribution_ratio", 0);
            }
        };
        runtime(Map.of("skuSalesHistoryExecutor", executor)).execute("sku_sales_history",
                Map.of("sku", "可乐", "time_range", TimeSpec.recentDays(7)), context());
        assertEquals(new DateRange("2026-10-27", "2026-11-03"), received.get().get("time_range"));
        assertEquals(new DateRange("2026-10-20", "2026-10-27"), received.get().get("baseline"));
    }
    @Test void businessArgumentsGetTheirOwnResolvedTimeAndInvalidOutputCannotBeStored() {
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        CapabilityExecutor executor = new CapabilityExecutor() {
            public String name() { return "stockout_query"; }
            public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
                calls.incrementAndGet(); received.set(params);
                return Map.of("stockout_events", List.of(), "comment", "无记录");
            }
        };
        var runtime = runtime(Map.of("stockoutQueryExecutor", executor));
        runtime.execute("stockout_query", Map.of("time_range", TimeSpec.recentDays(7)), context());
        assertEquals(new DateRange("2026-10-27", "2026-11-03"), received.get().get("time_range"));
        assertFalse(received.get().containsKey("baseline"));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute("stockout_query", Map.of("time_range", TimeSpec.dates("2026-11-03", "2026-11-03")), context()));
        assertEquals(1, calls.get());
        CapabilityExecutor invalid = new CapabilityExecutor() {
            public String name() { return "stockout_query"; }
            public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) { return Map.of("stockout_events", "bad"); }
        };
        var session = runtime(Map.of("stockoutQueryExecutor", invalid)).newSession(context());
        session.loadTools(List.of("stockout_query"));
        assertThrows(IllegalArgumentException.class, () -> session.execute("bad", "stockout_query", Map.of()));
        assertTrue(session.history().isEmpty());
    }
}
