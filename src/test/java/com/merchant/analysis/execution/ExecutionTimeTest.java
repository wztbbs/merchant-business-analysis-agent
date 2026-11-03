package com.merchant.analysis.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.*;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.time.*;
import com.merchant.analysis.understanding.QueryUnderstanding;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionTimeTest {
    private static TimeResolver resolver(String instant) {
        return new TimeResolver(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
    }

    @Test void persistedPlanResumeUsesFrozenDatesAndPassesExplicitRanges() throws Exception {
        CapabilityCatalog catalog = new CapabilityCatalog();
        PlaybookLoader loader = new PlaybookLoader(catalog);
        TimeResolver original = resolver("2026-11-03T00:00:00Z");
        var time = original.resolvePeriods(TimeSpec.calendar("month", -1), null, original.capture());
        QueryUnderstanding u = new QueryUnderstanding("查询销售", "sales_amount", TimeSpec.calendar("month", -1), null,
                new QueryUnderstanding.Entity(null), null, null, null, null,
                new QueryUnderstanding.Scene("metric_query_report", List.of())).withResolvedTime(time);
        ObjectMapper mapper = new ObjectMapper();
        u = RunRepository.understandingFromJson(mapper.writeValueAsString(u));
        Plan plan = RunRepository.planFromJson(mapper.writeValueAsString(new PlanWeaver(loader).weave(u)));
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        CapabilityExecutor executor = new CapabilityExecutor() {
            public String name() { return "sales_summary_query"; }
            public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
                received.set(params);
                return Map.of("current_total", 10, "baseline_total", 0, "metric_delta", 10,
                        "order_delta", 1, "aov_delta", 10, "current_orders", 1, "baseline_orders", 0, "current_aov", 10, "baseline_aov", 0);
            }
        };
        RunRepository repository = mock(RunRepository.class);
        when(repository.loadSuccessfulStepOutputs("run-1")).thenReturn(Map.of());
        ExecutionService service = new ExecutionService(catalog, Map.of("salesSummaryQueryExecutor", executor),
                resolver("2026-12-03T00:00:00Z"), repository, loader);
        try {
            AnalysisContext result = service.resume("run-1", plan, u);
            assertEquals(time.current(), result.current());
            assertEquals(time.current(), received.get().get("time_range"));
            assertEquals(time.baseline(), received.get().get("baseline"));
        } finally { service.shutdown(); }
    }

    @Test void legacyPersistedUnderstandingCanStillBeRead() {
        var old = RunRepository.understandingFromJson("""
            {"time_range":"this_month","comparison_baseline":"previous_month",
             "scene":{"primary_scene":"metric_query_report","factor_modules":[]}}
            """);
        assertEquals(TimeSpec.calendar("month", 0), old.time_range());
        assertEquals(TimeSpec.calendar("month", -2), old.comparison_baseline());
    }
}
