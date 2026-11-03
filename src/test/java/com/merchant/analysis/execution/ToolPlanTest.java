package com.merchant.analysis.execution;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.*;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.time.*;
import com.merchant.analysis.tool.*;
import com.merchant.analysis.understanding.QueryUnderstanding;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolPlanTest {
    private final CapabilityCatalog catalog = new CapabilityCatalog();
    private static PlanStep sum() {
        return new PlanStep("c1", "求和", null, List.of("sum"),
                Map.of("input", Map.of("kind", "values", "values", List.of("0.1", "0.2"))), Map.of(), List.of(), List.of("value"));
    }
    private static PlanStep ratio(List<String> dependencies, String reference) {
        return new PlanStep("c2", "倍数", null, List.of("ratio"),
                Map.of("input", Map.of("left", Map.of("$ref", reference), "right", "0.1")), Map.of(), dependencies, List.of("value", "percent"));
    }
    @Test void calculationNodesUseSamePlanRuntimeAndPersistableReferences() throws Exception {
        Plan plan = new Plan("calculation_test", List.of(sum(), ratio(List.of("c1"), "c1.value")));
        plan = RunRepository.planFromJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan));
        new PlanValidator(catalog).validate(plan);
        TimeResolver resolver = new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
        var time = resolver.resolvePeriods(TimeSpec.calendar("month", -1), null, resolver.capture());
        var u = new QueryUnderstanding("计算", "sales_amount", TimeSpec.calendar("month", -1), null,
                null, null, null, null, null, null).withResolvedTime(time);
        ToolRuntime runtime = new ToolRuntime(new ToolCatalog(catalog), catalog, Map.of(), new CalculationEngine(), resolver);
        ExecutionService service = new ExecutionService(runtime, resolver, mock(RunRepository.class), new PlaybookLoader(catalog));
        try {
            var result = service.execute(plan, u, "run-1");
            assertEquals("0.3", result.lookup("c1", "value"));
            assertEquals("3", result.lookup("c2", "value"));
        } finally { service.shutdown(); }
    }
    @Test void referencesMustHaveDeclaredProducerOutputAndDependency() {
        var validator = new PlanValidator(catalog);
        assertThrows(RuntimeException.class, () -> validator.validate(new Plan("test", List.of(sum(), ratio(List.of(), "c1.value")))));
        assertThrows(RuntimeException.class, () -> validator.validate(new Plan("test", List.of(sum(), ratio(List.of("c1"), "c1.missing")))));
        assertThrows(RuntimeException.class, () -> validator.validate(new Plan("test", List.of(sum(), ratio(List.of("c1"), "c1.value+1")))));
        assertDoesNotThrow(() -> validator.validate(new Plan("test", List.of(sum(), ratio(List.of("c1"), "c1.value")))));
    }
}
