package com.merchant.analysis.api;

import com.merchant.analysis.conclusion.ConclusionService;
import com.merchant.analysis.react.*;
import com.merchant.analysis.conversation.*;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.llm.MockLlmClient;
import com.merchant.analysis.persistence.*;
import com.merchant.analysis.plan.*;
import com.merchant.analysis.time.TimeResolver;
import com.merchant.analysis.understanding.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyzeRoutingTest {
    private final QueryUnderstandingService understanding = new QueryUnderstandingService(new MockLlmClient(),
            new ClassPathResource("prompts/query_understanding.txt"),
            new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null));
    private final ReactService react = mock(ReactService.class);
    private final PlanWeaver weaver = mock(PlanWeaver.class);
    private final PlanValidator validator = mock(PlanValidator.class);
    private final ExecutionService execution = mock(ExecutionService.class);
    private final ConclusionService conclusion = mock(ConclusionService.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final BadQueryCaseRepository badQueries = mock(BadQueryCaseRepository.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final SessionRecord session = new SessionRecord("session", "merchant", "user", "test", "active", null, null, null, null);
    private AnalyzeController controller() {
        when(conversations.createSession(any(), any(), any())).thenReturn(session);
        return new AnalyzeController(understanding, weaver, validator, execution, conclusion, runs, badQueries, conversations, react);
    }
    private static AnalyzeRequest request(String query) { return new AnalyzeRequest(query, null, null, null, null); }

    @Test void uncoveredRequestExecutesReactWithoutEnteringPlaybook() {
        when(runs.createRun(anyString(), any(), any(), any())).thenReturn("react-run");
        when(react.execute(eq("react-run"), any(), anyList())).thenReturn(new ReactResult(ReactStatus.COMPLETED,
                "品类结论", Map.of(), List.of(), Map.of("model_calls", 3)));
        var response = controller().analyze(request("上个月品类销售变化怎么样"));
        assertEquals(ReactStatus.COMPLETED, response.get("status"));
        assertEquals("react-run", response.get("run_id"));
        assertEquals("品类结论", response.get("conclusion"));
        verifyNoInteractions(weaver, validator, execution, conclusion);
        verify(react).execute(eq("react-run"), any(), anyList());
    }
    @Test void domainAndClarificationHaveDistinctResponses() {
        var controller = controller();
        assertEquals("OUT_OF_DOMAIN", controller.analyze(request("今天天气怎么样")).get("status"));
        assertEquals("NEEDS_CLARIFICATION", controller.analyze(request("这几个商品哪些缺货了")).get("status"));
        verifyNoInteractions(weaver, validator, execution, runs, conclusion);
    }
    @Test void coveredFollowUpExecutesWithRewrittenQueryRecordedAlongsideOriginal() throws Exception {
        var previous = understanding.understand("上个月销售额是多少").understanding();
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(previous);
        var chain = new ChainContext("root", "old-run", "上个月销售额是多少", json);
        when(conversations.prepareContext(any(), any(), any())).thenReturn(new ConversationService.CandidateContext("merchant", "user", session, List.of(chain)));
        when(conversations.settle(any(), any(), any())).thenReturn(session);
        Plan plan = new Plan("metric_query_report", List.of());
        when(weaver.weave(any())).thenReturn(plan);
        when(runs.createRun(anyString(), eq(plan), any(), eq("root"))).thenReturn("new-run");
        when(execution.execute(eq(plan), any(), eq("new-run"))).thenAnswer(invocation -> {
            QueryUnderstanding u = invocation.getArgument(1);
            return new AnalysisContext(u, u.resolved_time().current(), u.resolved_time().baseline());
        });
        when(conclusion.conclude(anyString(), eq(plan), any())).thenReturn("订单量结论");
        var response = controller().analyze(request("那订单量呢"));
        assertEquals("SUCCESS", response.get("status"));
        assertEquals("那订单量呢", response.get("original_query"));
        assertTrue(response.get("resolved_query").toString().contains("2026-10-01"));
        assertTrue(response.get("resolved_query").toString().contains("订单量"));
        verify(conversations).recordRunTurn(eq(session), any(), eq("那订单量呢"), eq(response.get("resolved_query").toString()), eq("new-run"));
        verify(validator).validate(plan);
    }
}
