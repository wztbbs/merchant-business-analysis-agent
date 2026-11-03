package com.merchant.analysis.tool;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.time.TimeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ToolWiringTest {
    @Test void springWiresSharedRegistryAndExecutionService() {
        new ApplicationContextRunner()
                .withUserConfiguration(CapabilityCatalog.class, ToolCatalog.class, ToolRuntime.class,
                        CalculationEngine.class, TimeResolver.class, PlaybookLoader.class, ExecutionService.class)
                .withBean(RunRepository.class, () -> mock(RunRepository.class))
                .withBean("stockoutQueryExecutor", CapabilityExecutor.class, () -> new CapabilityExecutor() {
                    public String name() { return "stockout_query"; }
                    public Map<String, Object> execute(Map<String, Object> args, AnalysisContext ctx) { return Map.of(); }
                })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(ToolRuntime.class));
                    assertNotNull(context.getBean(ExecutionService.class));
                    assertEquals(18, context.getBean(ToolCatalog.class).summaries().size());
                });
    }
}
