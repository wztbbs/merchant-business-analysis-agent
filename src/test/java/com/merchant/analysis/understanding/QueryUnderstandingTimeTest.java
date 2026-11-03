package com.merchant.analysis.understanding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.llm.LlmClient;
import com.merchant.analysis.llm.MockLlmClient;
import com.merchant.analysis.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueryUnderstandingTimeTest {
    @Test void springWiresConfiguredTimeResolverIntoUnderstanding() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(TimeResolver.class, MockLlmClient.class, QueryUnderstandingService.class,
                        com.merchant.analysis.execution.CapabilityCatalog.class, com.merchant.analysis.scene.ReactSceneCatalog.class,
                        com.merchant.analysis.playbook.PlaybookLoader.class, PlaybookMatcher.class)
                .withPropertyValues("business.time-zone=UTC", "llm.provider=mock")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("UTC", context.getBean(TimeResolver.class).capture().timezone());
                    assertNotNull(context.getBean(QueryUnderstandingService.class));
                });
    }

    private static TimeResolver resolver(String instant) {
        return new TimeResolver(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
    }
    private static QueryUnderstandingService service(LlmClient llm, TimeResolver resolver) {
        return new QueryUnderstandingService(llm, new ClassPathResource("prompts/query_understanding.txt"), resolver);
    }

    @Test void mockProducesAndResolvesAllThreeTypes() {
        var service = service(new MockLlmClient(), resolver("2026-11-03T00:00:00Z"));
        assertEquals(new DateRange("2026-11-01", "2026-11-03"),
                service.understand("本月销售额是多少").understanding().resolved_time().current());
        assertEquals(new DateRange("2026-10-27", "2026-11-03"),
                service.understand("最近7天销售额是多少").understanding().resolved_time().current());
        assertEquals(new DateRange("2026-10-10", "2026-10-21"),
                service.understand("2026-10-10到2026-10-20销售额是多少").understanding().resolved_time().current());
    }

    @Test void capturesBeforeModelCallAndIgnoresModelProvidedResolvedTime() throws Exception {
        class MutableClock extends Clock {
            Instant instant = Instant.parse("2026-10-31T15:59:59Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return instant; }
        }
        MutableClock clock = new MutableClock();
        TimeResolver resolver = new TimeResolver(clock, ZoneId.of("Asia/Shanghai"), null);
        LlmClient llm = prompt -> {
            assertTrue(prompt.contains("业务日期=2026-10-31"));
            clock.instant = Instant.parse("2026-11-01T01:00:00Z");
            return """
                {"metric":"sales_amount","time_range":{"type":"calendar_period","unit":"month","offset":-1},
                 "comparison_baseline":null,"scene":{"primary_scene":"metric_query_report","factor_modules":[]},
                 "task":{"resolved_query":"上月销售额是多少","in_domain":true,"requirements":[{"action":"query","metric":"sales_amount","scope":"shop","entities":[]}]},
                 "matched_playbook":"metric_query_report",
                 "resolved_time":{"garbage":"must be ignored"}}
                """;
        };
        QueryUnderstanding u = service(llm, resolver).understand("上月销售额是多少").understanding();
        assertEquals(new DateRange("2026-09-01", "2026-10-01"), u.resolved_time().current());
        assertEquals("2026-10-31", u.resolved_time().context().reference_date());
        assertEquals(u, new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(u), QueryUnderstanding.class));
    }

    @Test void metricFollowUpKeepsOriginalRangesAcrossMonthBoundary() throws Exception {
        QueryUnderstanding previous = service(new MockLlmClient(), resolver("2026-11-03T00:00:00Z"))
                .understand("上个月销售额是多少").understanding();
        var chains = List.of(new ChainContext("run-1", "run-1", "上个月销售额是多少", new ObjectMapper().writeValueAsString(previous)));
        UnderstandingResult result = service(new MockLlmClient(), resolver("2026-12-03T00:00:00Z"))
                .understand("那订单量呢", chains);
        assertTrue(result.followUp());
        assertEquals("order_count", result.understanding().metric());
        assertEquals(previous.resolved_time(), result.understanding().resolved_time());
    }
}
