package com.merchant.analysis.conversation;

import com.merchant.analysis.llm.MockLlmClient;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.*;
import com.merchant.analysis.understanding.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationContextTest {
    @Test void historyOnlyExposesSuccessfulRunsAndPreservesFullFactsAndRewrite() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/tables.sql")).execute(ds);
            var jdbc = new JdbcTemplate(ds);
            var runs = new RunRepository(jdbc);
            var repository = new ConversationRepository(jdbc, runs);
            var conversations = new ConversationService(repository);
            var session = conversations.createSession("m", "u", "上个月销售额是多少");
            var result = new QueryUnderstandingService(new MockLlmClient(), new ClassPathResource("prompts/query_understanding.txt"))
                    .understand("上个月销售额是多少");
            var plan = new Plan("metric_query_report", List.of(new PlanStep("S1", "查询", null,
                    List.of("sales_summary_query"), Map.of(), Map.of(), List.of(), List.of("current_total"))));
            String success = runs.createRun("上个月销售额是多少", plan, result.understanding(), null);
            List<Map<String, Object>> fullRows = new ArrayList<>();
            for (int i=0;i<10;i++) fullRows.add(Map.of("sku_id", "s"+i));
            runs.markStepSuccess(success, "S1", Map.of("current_total", 123, "items", fullRows));
            runs.finishRun(success, "SUCCESS", false, "结论", null);
            conversations.recordRunTurn(session, result, "原问题", "补全后的问题", success);
            String failed = runs.createRun("失败的请求", plan, result.understanding(), null);
            runs.finishRun(failed, "FAILED", false, null, "error");
            conversations.recordRunTurn(session, result, "失败的请求", "失败的请求", failed);
            var chains = repository.findRecentChainTailsBySession(session.sessionId());
            assertEquals(1, chains.size());
            assertEquals(success, chains.get(0).runId());
            assertEquals(10, ((List<?>) chains.get(0).stepResults().get("S1").get("items")).size());
            assertEquals("补全后的问题", repository.listTurns(session.sessionId()).stream().filter(t -> success.equals(t.runId())).findFirst().orElseThrow().resolvedQuery());
            conversations.recordStandaloneTurn(session, result, "原追问", "待进一步查询");
            assertTrue(repository.listTurns(session.sessionId()).stream().anyMatch(t -> "原追问".equals(t.userQuery())
                    && result.understanding().task().resolved_query().equals(t.resolvedQuery())));
        } finally { ds.destroy(); }
    }
}
