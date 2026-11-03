package com.merchant.analysis.plan;

import com.merchant.analysis.execution.CapabilityCatalog;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.understanding.QueryUnderstanding;
import com.merchant.analysis.time.TimeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 编织的最小单测：给定意图，编织出的 plan 必须通过 PlanValidator 校验，
 * 且下架因子步骤存在、依赖关系正确。
 */
class PlanWeaverTest {

    private final CapabilityCatalog catalog = new CapabilityCatalog();
    private final PlaybookLoader loader = new PlaybookLoader(catalog);
    private final PlanWeaver weaver = new PlanWeaver(loader);
    private final PlanValidator validator = new PlanValidator(catalog);

    @Test
    void diagnosisPlanContainsDelistingFactorAndPassesValidation() {
        QueryUnderstanding understanding = new QueryUnderstanding(
                "诊断销售额下滑原因", "sales_amount", TimeSpec.calendar("month", -1), null,
                new QueryUnderstanding.Entity("无糖可乐"), "delisting", null, null, null,
                new QueryUnderstanding.Scene("metric_change_diagnosis",
                        List.of("delisting", "campaign", "traffic", "price", "stockout")));

        Plan plan = weaver.weave(understanding);

        // 通过结构与供给校验（不抛异常即通过）
        assertDoesNotThrow(() -> validator.validate(plan));

        // 骨架固定步 + 收尾步存在（运行时 id 按编织顺序统一分配）
        assertNotNull(plan.step("S1"));
        assertNotNull(plan.step("S2"));
        assertNotNull(plan.step("S9"));

        // 下架因子两步紧跟 S2 之后实例化为 S3/S4
        PlanStep s3 = plan.step("S3");
        assertEquals("delisting", s3.factor());
        assertEquals(List.of("sku_status_query"), s3.capabilities());
        assertEquals("无糖可乐", s3.params().get("sku"));

        PlanStep s4 = plan.step("S4");
        assertEquals("delisting", s4.factor());
        // quantify_delisting_impact depends_on [confirm_delisting_event] → [S3]；
        // inputs 的语义引用 @metric_delta 反查为 S1.metric_delta 并自动补依赖 S1
        assertTrue(s4.dependsOn().containsAll(List.of("S3", "S1")));
        assertEquals("S1.metric_delta", s4.inputs().get("metric_delta"));

        // 默认因子四步（campaign/traffic/price/stockout）依次挂载
        assertEquals("campaign", plan.step("S5").factor());
        assertEquals("traffic", plan.step("S6").factor());
        assertEquals("price", plan.step("S7").factor());
        assertEquals("stockout", plan.step("S8").factor());

        // 收尾步依赖全部前序步骤
        assertEquals(9, plan.steps().size());
        assertEquals(8, plan.step("S9").dependsOn().size());
        assertTrue(plan.step("S9").dependsOn().containsAll(List.of("S1", "S4", "S8")));
    }

    @Test
    void queryReportPlanPassesValidation() {
        QueryUnderstanding understanding = new QueryUnderstanding(
                "查询指标数值", "sales_amount", TimeSpec.calendar("month", -1), null,
                new QueryUnderstanding.Entity(null), null, null, null, null,
                new QueryUnderstanding.Scene("metric_query_report", List.of()));

        Plan plan = weaver.weave(understanding);

        assertDoesNotThrow(() -> validator.validate(plan));
        assertEquals(List.of("S1", "S2"), plan.steps().stream().map(PlanStep::stepId).toList());
    }
}
