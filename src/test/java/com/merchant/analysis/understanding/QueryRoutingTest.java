package com.merchant.analysis.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.merchant.analysis.llm.*;
import com.merchant.analysis.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.merchant.analysis.understanding.RoutingDecision.Mode.*;

class QueryRoutingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private QueryUnderstandingService service(LlmClient llm) {
        return new QueryUnderstandingService(llm, new ClassPathResource("prompts/query_understanding.txt"),
                new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null));
    }
    private QueryUnderstanding understand(String query) { return service(new MockLlmClient()).understand(query).understanding(); }

    @Test void oneModelCallProvidesRewriteAndBothCatalogs() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(prompt -> {
            calls.incrementAndGet();
            assertTrue(prompt.contains("<PLAYBOOK_CATALOG>"));
            assertTrue(prompt.contains("category_structure_review"));
            assertFalse(prompt.contains("recommended_tools"));
            assertFalse(prompt.contains("先查询 sku_sales_breakdown"));
            return new MockLlmClient().chat(prompt);
        });
        var result = service.understand("上个月销售额是多少").understanding();
        assertEquals(1, calls.get());
        assertEquals("上个月销售额是多少", result.task().original_query());
        assertEquals("上个月销售额是多少", result.task().resolved_query());
        assertEquals(PLAYBOOK, result.routing().route());
        assertEquals("metric_query_report", result.routing().matched_playbook());
    }

    @ParameterizedTest
    @CsvSource(delimiter='|', value={
        "上个月哪些品类上涨、哪些下降？再看看当前销售规模最大的品类。|category_structure_review",
        "上个月发生过缺货的商品里，哪些销售也下降了？|stockout_sales_crosscheck",
        "上个月变化最大的商品有哪些？挑销售下降的几个，看看有没有下架、缺货或者价格变化。|top_sku_drilldown",
        "对比无糖可乐和巧克力上个月的销售变化，看看价格有没有变化。|multi_sku_review"
    })
    void uncoveredRequestsGetTextScene(String query, String scene) {
        var result = understand(query);
        assertTrue(result.task().in_domain());
        assertEquals(REACT, result.routing().route());
        assertNull(result.routing().matched_playbook());
        assertEquals(scene, result.routing().matched_react_scene());
    }
    @Test void domainAndCapabilityCoverageAreIndependent() {
        var result = understand("上个月复购率是多少");
        assertTrue(result.task().in_domain());
        assertEquals("repeat_purchase", result.task().requirements().get(0).metric());
        assertEquals(REACT, result.routing().route());
        assertNull(result.routing().matched_react_scene());
        assertEquals(OUT_OF_DOMAIN, understand("今天天气怎么样").routing().route());
    }
    @Test void skuQueryAndOrderCountDiagnosisDoNotFallIntoWholeShopTemplates() {
        assertEquals(REACT, understand("上个月无糖可乐销售额是多少").routing().route());
        assertEquals(REACT, understand("为什么上个月订单量下降了").routing().route());
        assertEquals(PLAYBOOK, understand("为什么上个月销售额下降了").routing().route());
        assertEquals(PLAYBOOK, understand("我把无糖可乐下架了影响大吗").routing().route());
    }
    @Test void mixedRequestsAndDifferentWindowsRemainComplete() {
        String query = "上个月销售额是多少，顺便看看缺货商品";
        var mixed = understand(query);
        assertEquals(query, mixed.task().resolved_query());
        assertEquals(2, mixed.task().requirements().size());
        assertEquals(REACT, mixed.routing().route());
        var windows = understand("上个月和这个月销售额分别是多少");
        assertEquals(2, windows.task().requirements().size());
        assertEquals(REACT, windows.routing().route());
        assertEquals(TimeSpec.calendar("month", 0), windows.task().requirements().get(1).time_range());
    }
    @Test void unavailableDatesClarifyWithoutChangingDomain() {
        var today = understand("今天实时销售额是多少");
        assertTrue(today.task().in_domain());
        assertEquals(CLARIFY, today.routing().route());
        var explicit = understand("2026-11-02到2026-11-03销售额是多少");
        assertEquals(CLARIFY, explicit.routing().route());
        assertEquals("2026-11-03", explicit.time_range().end());
    }
    @Test void codeRejectsUnknownIdsAndFalseFullCoverage() throws Exception {
        var original = understand("上个月销售额是多少");
        var payload = (ObjectNode) JSON.valueToTree(original);
        ((ObjectNode) payload.path("task")).put("original_query", "伪造原文");
        payload.put("matched_playbook", "metric_query_report");
        payload.put("matched_react_scene", "../../unknown");
        ((ObjectNode) payload.path("task").path("requirements").get(0)).put("scope", "category");
        var result = service(prompt -> payload.toString()).understand("原始的品类查询").understanding();
        assertEquals("原始的品类查询", result.task().original_query());
        assertEquals(REACT, result.routing().route());
        assertNull(result.routing().matched_react_scene());
        payload.put("matched_playbook", "unknown_playbook");
        assertEquals(REACT, service(prompt -> payload.toString()).understand("原始问题").understanding().routing().route());
    }
    @Test void collectiveReferenceBindsFullOriginalResultRatherThanTruncatedPreview() throws Exception {
        var previous = understand("为什么上个月销售额下降了");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i=0; i<10; i++) rows.add(Map.of("sku_id", "s"+i, "name", "商品"+i, "delta", -i));
        var chain = new ChainContext("root", "run-1", "为什么上个月销售额下降了", JSON.writeValueAsString(previous),
                Map.of("S2", Map.of("top_contributors", rows)));
        var summary = HistoryFacts.summaries(chain).get(0);
        assertEquals(10, summary.count());
        assertEquals(3, ((List<?>) summary.preview()).size());
        assertTrue(summary.preview_truncated());
        var result = service(new MockLlmClient()).understand("这几个商品哪些缺货了", List.of(chain));
        assertEquals(REACT, result.understanding().routing().route());
        assertEquals(List.of(new QueryTask.FactReference("run-1", "S2.top_contributors")), result.understanding().task().fact_references());
        assertFalse(result.understanding().task().resolved_query().contains("商品9"));
        HistoryFacts.validate(new QueryTask.FactReference("run-1", "S2.top_contributors.9.sku_id"), List.of(chain));
    }
    @Test void ambiguousOrMissingHistoryRequiresClarification() throws Exception {
        var previous = understand("上个月销售额是多少");
        String json = JSON.writeValueAsString(previous);
        var chains = List.of(new ChainContext("a", "a", "上个月销售额是多少", json), new ChainContext("b", "b", "另一项销售分析", json));
        assertEquals(CLARIFY, service(new MockLlmClient()).understand("那订单量呢", chains).understanding().routing().route());
        assertEquals(CLARIFY, service(new MockLlmClient()).understand("这几个商品哪些缺货了").understanding().routing().route());
        ObjectNode payload = (ObjectNode) JSON.valueToTree(previous);
        payload.put("is_follow_up", true); payload.put("ref_run_id", "a"); payload.put("follow_up_type", "result_reference");
        ((ObjectNode) payload.path("task")).set("fact_references", JSON.valueToTree(List.of(Map.of("run_id", "a", "path", "S2.missing"))));
        assertEquals(CLARIFY, service(prompt -> payload.toString()).understand("看看上次结果", chains).understanding().routing().route());
    }
}
