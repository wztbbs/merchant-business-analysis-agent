package com.merchant.analysis.understanding;

import com.merchant.analysis.llm.MockLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追问判定与组装（mock LLM 链路）：候选链尾注入 prompt 后，
 * 省略式追问继承链尾 understanding 组装；带完整时间词的问题不判追问。
 */
class QueryUnderstandingFollowUpTest {

    private final QueryUnderstandingService service = new QueryUnderstandingService(
            new MockLlmClient(), new ClassPathResource("prompts/query_understanding.txt"));

    /** 链尾：run-1，上月销售额诊断 + 无糖可乐下架假设 */
    private static List<ChainContext> chains() {
        String understandingJson = """
                {"business_intent": "诊断销售额下滑原因", "metric": "sales_amount",
                 "time_range": "last_month", "comparison_baseline": "previous_month",
                 "entity": {"sku": "无糖可乐"}, "candidate_factor": "delisting",
                 "suggest_action": null, "suggest_object": null, "suggest_entity": null,
                 "scene": {"primary_scene": "metric_change_diagnosis",
                           "factor_modules": ["delisting", "campaign", "traffic", "price", "stockout"]}}
                """;
        return List.of(new ChainContext("run-1", "run-1", "为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？",
                understandingJson));
    }

    @Test
    void newQuestionWithoutChainsIsNotFollowUp() {
        UnderstandingResult r = service.understand("为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？");

        assertFalse(r.followUp());
        assertNull(r.refRunId());
        assertEquals("metric_change_diagnosis", r.understanding().primaryScene());
        assertEquals("sales_amount", r.understanding().metric());
    }

    @Test
    void metricSwitchFollowUpInheritsChainTailAndOverridesMetric() {
        UnderstandingResult r = service.understand("那订单量呢", chains());

        assertTrue(r.followUp());
        assertEquals("metric_switch", r.followUpType());
        assertEquals("run-1", r.refRunId());
        assertTrue(r.needsRerun());
        // 继承链尾槽位，只覆盖 metric
        assertEquals("order_count", r.understanding().metric());
        assertEquals(com.merchant.analysis.time.TimeSpec.calendar("month", -1), r.understanding().time_range());
        assertEquals("无糖可乐", r.understanding().skuOrNull());
        assertEquals("delisting", r.understanding().candidate_factor());
        assertEquals("metric_change_diagnosis", r.understanding().primaryScene());
    }

    @Test
    void factorDetailFollowUpDoesNotRerun() {
        UnderstandingResult r = service.understand("下架影响到底多少", chains());

        assertTrue(r.followUp());
        assertEquals("factor_detail", r.followUpType());
        assertEquals("run-1", r.refRunId());
        assertFalse(r.needsRerun());
    }

    @Test
    void completeNewQuestionWithChainsIsNotFollowUp() {
        // 带完整时间词 → 新问题，不承接链
        UnderstandingResult r = service.understand("上个月销售额是多少", chains());

        assertFalse(r.followUp());
        assertNull(r.refRunId());
        assertEquals("metric_query_report", r.understanding().primaryScene());
    }
}
