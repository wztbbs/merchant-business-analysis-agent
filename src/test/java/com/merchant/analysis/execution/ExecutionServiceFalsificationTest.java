package com.merchant.analysis.execution;

import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.Plan;
import com.merchant.analysis.plan.PlanStep;
import com.merchant.analysis.plan.PlanWeaver;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.time.TimeResolver;
import com.merchant.analysis.understanding.QueryUnderstanding;
import com.merchant.analysis.time.TimeSpec;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 假设证伪的执行期行为：证伪规则（falsification）声明在因子 playbook 里，
 * 仅当该因子是用户假设（candidate_factor）时才生效——命中规则标记 hypothesis_broken
 * 并跳过该因子后续步骤；非用户假设的步骤产出同样为空也不标记。
 */
class ExecutionServiceFalsificationTest {

    private final CapabilityCatalog catalog = new CapabilityCatalog();
    private final PlaybookLoader loader = new PlaybookLoader(catalog);
    private final PlanWeaver weaver = new PlanWeaver(loader);
    private final RunRepository runRepository = mock(RunRepository.class);

    /** sku_status_query 桩：查不到下架事件（delisting_time = null）；其余能力返回满足接口契约的空业务结果 */
    private ExecutionService serviceWithNoDelistingEvent(Plan plan) {
        Map<String, CapabilityExecutor> executors = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            for (String capability : step.capabilities()) {
                executors.put(catalog.executorBean(capability), stubExecutor(validOutput(capability)));
            }
        }
        return new ExecutionService(catalog, executors, new TimeResolver(), runRepository, loader);
    }

    private static Map<String, Object> validOutput(String capability) {
        return switch (capability) {
            case "sku_status_query" -> {
                Map<String, Object> out = new HashMap<>();
                out.put("sku", "无糖可乐"); out.put("sku_id", "s1");
                out.put("delisting_time", null); out.put("listing_status", "在架");
                yield out;
            }
            case "sales_summary_query" -> Map.of("current_total", 0, "baseline_total", 0, "metric_delta", 0,
                    "order_delta", 0, "aov_delta", 0, "current_orders", 0, "baseline_orders", 0, "current_aov", 0, "baseline_aov", 0);
            case "sku_sales_breakdown" -> Map.of("top_contributors", List.of());
            case "category_sales_breakdown" -> Map.of("category_breakdown", List.of());
            case "sku_sales_history" -> Map.of("sku_total_sales", 0, "sku_daily_avg_sales", 0, "sku_sales_share", 0,
                    "sales_delta", 0, "contribution_ratio", 0);
            case "substitute_sku_analysis" -> java.util.Collections.singletonMap("substitution_effect", null);
            case "campaign_query" -> Map.of("campaign_comparison", Map.of("current_campaigns", List.of(),
                    "baseline_campaigns", List.of(), "current_campaign_sales", 0, "baseline_campaign_sales", 0, "comment", ""));
            case "traffic_conversion_query" -> Map.of("traffic_delta", Map.of("current_exposure", 0, "baseline_exposure", 0,
                    "exposure_delta", 0, "current_visit_uv", 0, "baseline_visit_uv", 0, "visit_uv_delta", 0),
                    "conversion_delta", Map.of("current_conversion", 0, "baseline_conversion", 0, "conversion_delta", 0));
            case "price_change_query" -> Map.of("price_changes", List.of(), "scope", "explicit", "comment", "");
            case "stockout_query" -> Map.of("stockout_events", List.of(), "comment", "");
            default -> throw new IllegalArgumentException(capability);
        };
    }

    private static CapabilityExecutor stubExecutor(Map<String, Object> outputs) {
        return new CapabilityExecutor() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
                return new LinkedHashMap<>(outputs);
            }
        };
    }

    private static QueryUnderstanding diagnosisWithDelistingCandidate() {
        return new QueryUnderstanding(
                "诊断销售额下滑原因", "sales_amount", TimeSpec.calendar("month", -1), null,
                new QueryUnderstanding.Entity("无糖可乐"), "delisting", null, null, null,
                new QueryUnderstanding.Scene("metric_change_diagnosis",
                        List.of("delisting", "campaign", "traffic", "price", "stockout")));
    }

    @Test
    void candidateFactorFalsifiedSkipsOwnSubsequentStepsOnly() {
        QueryUnderstanding understanding = diagnosisWithDelistingCandidate();
        Plan plan = weaver.weave(understanding);
        ExecutionService service = serviceWithNoDelistingEvent(plan);

        AnalysisContext ctx = service.execute(plan, understanding, "run-1");

        // 用户假设因子 delisting 的确认步骤查不到下架事件 → 证伪
        assertTrue(ctx.hypothesisBroken());
        assertEquals("delisting", ctx.falsifiedFactor());
        // delisting 后续量化步骤被跳过（S3=确认下架事件，S4=量化下架影响）
        verify(runRepository).markStepSkipped(eq("run-1"), eq("S4"), anyString());
        assertFalse(ctx.stepResults().containsKey("S4"));
        // 其他因子步骤不受影响，照常执行
        assertTrue(ctx.stepResults().containsKey("S5"));
        assertTrue(ctx.stepResults().containsKey("S8"));
    }

    @Test
    void nullRuleOutputFromNonCandidateStepDoesNotFalsify() {
        // 评估场景：骨架确认步骤同样跑 sku_status_query 并产出 delisting_time=null，
        // 但它是骨架步骤（非用户假设因子），candidate 是 stockout（无证伪规则）→ 不标记
        QueryUnderstanding understanding = new QueryUnderstanding(
                "评估缺货影响", "sales_amount", TimeSpec.calendar("month", -1), null,
                new QueryUnderstanding.Entity("无糖可乐"), "stockout", null, null, null,
                new QueryUnderstanding.Scene("factor_impact_evaluation", List.of("stockout")));
        Plan plan = weaver.weave(understanding);
        ExecutionService service = serviceWithNoDelistingEvent(plan);

        AnalysisContext ctx = service.execute(plan, understanding, "run-2");

        assertFalse(ctx.hypothesisBroken());
        verify(runRepository, never()).markStepSkipped(anyString(), anyString(), anyString());
    }

    @Test
    void resumeReplaysFalsificationRuleOnCompletedSteps() {
        QueryUnderstanding understanding = diagnosisWithDelistingCandidate();
        Plan plan = weaver.weave(understanding);
        // 已完成到 S3（确认下架事件，产出 delisting_time=null），从 S4 起续跑
        Map<String, Object> s3Outputs = new HashMap<>();
        s3Outputs.put("delisting_time", null);
        s3Outputs.put("listing_status", "在架");
        Map<String, Map<String, Object>> completed = Map.of(
                "S1", Map.of("metric_delta", -100),
                "S2", Map.of("top_contributors", List.of(), "category_breakdown", List.of()),
                "S3", s3Outputs);
        when(runRepository.loadSuccessfulStepOutputs("run-3")).thenReturn(completed);
        ExecutionService service = serviceWithNoDelistingEvent(plan);

        AnalysisContext ctx = service.resume("run-3", plan, understanding);

        assertTrue(ctx.hypothesisBroken());
        assertEquals("delisting", ctx.falsifiedFactor());
        verify(runRepository).markStepSkipped(eq("run-3"), eq("S4"), anyString());
        assertFalse(ctx.stepResults().containsKey("S4"));
    }
}
