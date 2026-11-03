package com.merchant.analysis.react;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.llm.MockLlmClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.merchant.analysis.react.ReactFixture.*;

class ReactServiceTest {
    static final String QUERY="上个月哪些品类上涨、哪些下降？再看看当前销售规模最大的品类。";
    @Test void mockExecutesRealSqlLoadsSchemasAndCalculatesMaximum() {
        try(var f=new ReactFixture(new MockLlmClient())){
            var run=f.run(QUERY);var result=run.result();
            assertEquals(ReactStatus.COMPLETED,result.status());
            assertTrue(result.answer().contains("饮料"));assertTrue(result.answer().contains("100"));
            assertEquals(5,result.stats().get("model_calls"));assertEquals(2,result.stats().get("tool_calls"));
            assertEquals("SUCCESS",f.runs.findRun(run.id()).status());
            assertTrue(f.repository.events(run.id()).stream().anyMatch(e->e.get("kind").equals("LOAD_TOOLS")));
            assertTrue(f.repository.events(run.id()).stream().anyMatch(e->e.get("name").equals("max")));
            var state=f.repository.load(run.id());assertEquals("category_structure_review",state.scene);
            assertTrue(state.finalized);assertEquals("100",result.stepResults().get("T2").get("value"));
            f.runs.markRunRunning(run.id()); // 模拟状态已保存、外层运行状态尚未提交时进程中断。
            var resumed=f.service.resume(run.id(),run.understanding());
            assertEquals("SUCCESS",f.runs.findRun(run.id()).status());
            assertEquals(result.stats().get("model_calls"),resumed.stats().get("model_calls"));
            assertEquals(result.answer(),resumed.answer());
        }
    }
    @Test void unmatchedSceneStillExploresAndUnsupportedMetricStopsHonestly() {
        try(var f=new ReactFixture(new MockLlmClient())){
            var run=f.run("上个月无糖可乐销售额是多少");
            assertNull(run.understanding().routing().matched_react_scene());
            assertEquals(ReactStatus.COMPLETED,run.result().status());
            assertEquals(60.0,run.result().stepResults().get("T1").get("sku_total_sales"));
            var missing=f.run("上个月复购率是多少");
            assertEquals(ReactStatus.CAPABILITY_MISSING,missing.result().status());
            assertEquals(0,missing.result().stats().get("tool_calls"));
            assertTrue(missing.result().stepResults().isEmpty());
        }
    }
    @Test void repeatedLoadsStopWithoutInfiniteModelCalls() {
        var script=new Script(n->load("sum"));
        try(var f=new ReactFixture(script)){
            var run=f.run(QUERY);
            assertEquals(ReactStatus.LOOP_DETECTED,run.result().status());
            assertEquals(4,script.decisions.get());assertEquals(5,script.calls.get());
            assertEquals(0,run.result().stats().get("tool_calls"));
        }
    }
    @Test void alternatingRepeatedCallsReuseResultsAndStop() {
        var engine=spy(new CalculationEngine());
        var script=new Script(n->n==1?load("sum","difference"):n%2==0?call("sum",Map.of("input",Map.of("kind","values","values",List.of(1,2)))):
                call("difference",Map.of("input",Map.of("left",7,"right",4))));
        try(var f=new ReactFixture(script,new ReactSettings(),Map.of(),engine)){
            var run=f.run(QUERY);
            assertEquals(ReactStatus.LOOP_DETECTED,run.result().status());
            assertEquals(5,run.result().stats().get("tool_calls"));
            verify(engine,times(1)).execute(eq("sum"),anyMap());verify(engine,times(1)).execute(eq("difference"),anyMap());
            assertEquals(3,f.repository.events(run.id()).stream().filter(e->e.get("status").equals("CACHED")).count());
        }
    }
    @Test void malformedActionsAndUnloadedCallsConsumeBudgetAndStop() {
        var malformed=new Script(n->"not-json");
        try(var f=new ReactFixture(malformed)){
            var run=f.run(QUERY);assertEquals(ReactStatus.LOOP_DETECTED,run.result().status());assertEquals(2,malformed.decisions.get());
        }
        var unloaded=new Script(n->call("sum",Map.of("input",Map.of("kind","values","values",List.of(1)))));
        try(var f=new ReactFixture(unloaded)){
            var run=f.run(QUERY);assertEquals(ReactStatus.LOOP_DETECTED,run.result().status());assertEquals(2,run.result().stats().get("tool_calls"));
            assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM step_run WHERE run_id=? AND status='FAILED'",Integer.class,run.id()));
        }
    }
    @Test void modelAndToolHardLimitsIncludeFinalAnswerAndFailedAttempts() {
        var settings=new ReactSettings();settings.setMaxModelCalls(3);
        var script=new Script(n->load(n==1?"sum":"avg"));
        try(var f=new ReactFixture(script,settings,Map.of(),new CalculationEngine())){
            var run=f.run(QUERY);assertEquals(ReactStatus.BUDGET_EXCEEDED,run.result().status());assertEquals(3,script.calls.get());
        }
        settings=new ReactSettings();settings.setMaxToolCalls(1);
        script=new Script(n->n==1?load("sum"):call("sum",Map.of("input",Map.of("kind","values","values",List.of(1)))));
        try(var f=new ReactFixture(script,settings,Map.of(),new CalculationEngine())){
            var run=f.run(QUERY);assertEquals(ReactStatus.BUDGET_EXCEEDED,run.result().status());assertEquals(1,run.result().stats().get("tool_calls"));
        }
    }
    @Test void unsupportedCompletionAndInventedNumbersCannotBeReturnedAsSuccess() {
        var script=new Script(n->finish());script.answer="销售额为99999999元。";
        try(var f=new ReactFixture(script)){
            var run=f.run(QUERY);assertEquals(ReactStatus.PARTIAL,run.result().status());
            assertFalse(run.result().answer().contains("99999999"));
            assertEquals("missing",run.result().coverage().get(0).status());
            assertTrue(f.repository.events(run.id()).stream().anyMatch(e->e.get("kind").equals("ANSWER")&&e.get("status").equals("FAILED")));
        }
    }
    @Test void resumeUsesPersistedCountersAndDeadlineInsteadOfNewBudget() {
        var script=new Script(n->{throw new AssertionError("不得重新进入决策循环");});
        try(var f=new ReactFixture(script)){
            var u=f.understanding.understand(QUERY).understanding();String id=f.create(u);
            var state=new ReactState();state.limits=fastSettings();state.limits.setMaxModelCalls(3);state.modelCalls=2;
            state.startedAt=System.currentTimeMillis();state.deadline=state.startedAt+2000;
            f.repository.save(id,state);f.settings.setMaxModelCalls(100);
            var result=f.service.resume(id,u);assertEquals(ReactStatus.BUDGET_EXCEEDED,result.status());assertEquals(3,result.stats().get("model_calls"));
            assertEquals(state.deadline,f.repository.load(id).deadline);assertEquals(0,script.decisions.get());
            String expired=f.create(u);state=new ReactState();state.limits=fastSettings();state.modelCalls=1;
            state.startedAt=System.currentTimeMillis()-5000;state.deadline=state.startedAt+3000;f.repository.save(expired,state);
            int before=script.calls.get();result=f.service.resume(expired,u);
            assertEquals(ReactStatus.BUDGET_EXCEEDED,result.status());assertEquals(before,script.calls.get());
        }
    }    @Test void promptBudgetStopsBeforeSchedulingAnOversizedDecision() {
        var script=new Script(n->{throw new AssertionError("上下文超限时不得调用决策模型");});
        var limits=new ReactSettings();limits.setMaxPromptChars(1000);
        try(var f=new ReactFixture(script,limits,Map.of(),new CalculationEngine())) {
            var run=f.run(QUERY);assertEquals(ReactStatus.BUDGET_EXCEEDED,run.result().status());
            assertEquals(0,script.decisions.get());assertEquals(0,run.result().stats().get("tool_calls"));
        }
    }

}
