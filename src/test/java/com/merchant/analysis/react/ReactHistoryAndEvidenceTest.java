package com.merchant.analysis.react;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.plan.*;
import com.merchant.analysis.tool.ToolInvocation;
import com.merchant.analysis.understanding.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.merchant.analysis.react.ReactFixture.*;

class ReactHistoryAndEvidenceTest {
    @Test void originalHistoryCollectionIsImportedAndCalculatedWithoutRequery() throws Exception {
        var script=new Script(n->switch(n){
            case 1->load("max");
            case 2->call("max",Map.of("input",Map.of("kind","field","collection",Map.of("$ref","H1.category_breakdown"),"field","current_total")));
            default->finish("T1.value");
        });
        try(var f=new ReactFixture(script)){
            var old=f.understanding.understand("上个月品类销售额是多少").understanding();
            var plan=new Plan("metric_query_report",List.of(new PlanStep("S1","品类",null,List.of("category_sales_breakdown"),Map.of(),Map.of(),List.of(),List.of("category_breakdown"))));
            String parent=f.runs.createRun("原品类查询",plan,old,null);
            Map<String,Object> output=Map.of("category_breakdown",List.of(Map.of("category","饮料","current_total",100,"baseline_total",50,"delta",50),
                    Map.of("category","零食","current_total",80,"baseline_total",50,"delta",30)));
            f.runs.markStepSuccess(parent,"S1",output);f.runs.finishRun(parent,"SUCCESS",false,"原结果",null);
            var task=new QueryTask("上次品类结果的最大销售额","查询上次相同结果的最高销售额",true,
                    List.of(new QueryTask.Requirement("query","sales_amount","category",List.of())),
                    List.of(new QueryTask.FactReference(parent,"S1.category_breakdown")),false,null);
            var u=old.withInterpretation(task,new RoutingDecision(RoutingDecision.Mode.REACT,null,null,"引用原数据计算"));
            String id=f.create(u);
            var chain=new ChainContext(parent,parent,"原品类查询",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(old),Map.of("S1",output));
            f.jdbc.update("UPDATE order_item SET amount=9999");
            var result=f.service.execute(id,u,List.of(chain));
            assertEquals(ReactStatus.COMPLETED,result.status());assertEquals("100",result.stepResults().get("T1").get("value"));
            assertEquals(List.of("max"),f.repository.load(id).calls.stream().map(c->c.invocation().name()).toList());
            assertEquals("H1.category_breakdown",f.repository.load(id).history.get(0).aliasPath());
            assertTrue(f.runs.loadSuccessfulStepOutputs(id).containsKey("H1"));
        }
    }
    @Test void derivedHistoryResultRetainsItsOriginalMetricAndTimeLineage() throws Exception {
        var following=new java.util.concurrent.atomic.AtomicBoolean();
        var script=new Script(n->switch(n){
            case 1->load("difference");
            case 2->call("difference",Map.of("input",Map.of("left",Map.of("$ref","H1.value"),"right",50)));
            default->finish("T1.value");
        });
        com.merchant.analysis.llm.LlmClient client=prompt->following.get()?script.chat(prompt):new com.merchant.analysis.llm.MockLlmClient().chat(prompt);
        try(var f=new ReactFixture(client)) {
            var parent=f.run(ReactServiceTest.QUERY); assertEquals(ReactStatus.COMPLETED,parent.result().status());
            following.set(true);
            var task=new QueryTask("上次最高品类销售额减50是多少", "用原最高销售额减50",true,
                    List.of(new QueryTask.Requirement("comparison","sales_amount","category",List.of())),
                    List.of(new QueryTask.FactReference(parent.id(),"T2.value")),false,null);
            var u=parent.understanding().withInterpretation(task,new RoutingDecision(RoutingDecision.Mode.REACT,null,null,"原计算结果继续计算"));
            String id=f.create(u);
            var chain=new ChainContext(parent.id(),parent.id(),ReactServiceTest.QUERY,
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(parent.understanding()),f.runs.loadSuccessfulStepOutputs(parent.id()));
            var result=f.service.execute(id,u,List.of(chain));
            assertEquals(ReactStatus.COMPLETED,result.status()); assertEquals("50",result.stepResults().get("T1").get("value"));
            assertEquals("category_sales_breakdown",f.repository.load(id).lineage.get("H1.value").get(0).tool());
        }
    }

    @Test void collectionPreviewCannotStandForAllRowsUntilInspected() {
        CapabilityExecutor large=new CapabilityExecutor(){
            public String name(){return "category_sales_breakdown";}
            public Map<String,Object> execute(Map<String,Object> args,AnalysisContext ctx){
                List<Map<String,Object>> rows=new ArrayList<>();
                for(int i=0;i<15;i++)rows.add(Map.of("category","品类"+i,"current_total",i,"baseline_total",0,"delta",i));
                return Map.of("category_breakdown",rows);
            }
        };
        var script=new Script(n->n==1?load("category_sales_breakdown"):n==2?call("category_sales_breakdown",Map.of()):finish("T1.category_breakdown"));
        try(var f=new ReactFixture(script,new ReactSettings(),Map.of("categorySalesBreakdownExecutor",large),new CalculationEngine())){
            var run=f.run("上个月品类销售额是多少");assertEquals(ReactStatus.PARTIAL,run.result().status());
        }
        script=new Script(n->switch(n){case 1->load("category_sales_breakdown");case 2->call("category_sales_breakdown",Map.of());
            case 3->ReactViews.json(Map.of("action","inspect_result","reference","T1.category_breakdown","offset",10,"limit",5));default->finish("T1.category_breakdown");});
        try(var f=new ReactFixture(script,new ReactSettings(),Map.of("categorySalesBreakdownExecutor",large),new CalculationEngine())){
            assertEquals(ReactStatus.COMPLETED,f.run("上个月品类销售额是多少").result().status());
        }
    }
    @Test void numericComparisonRequiresCalculationAndCorrectMetricNotJustTwoBareValues() {
        var script=new Script(n->n==1?load("sales_summary_query"):n==2?call("sales_summary_query",Map.of()):finish("T1.current_total","T1.baseline_total"));
        try(var f=new ReactFixture(script)){
            var run=f.run("上个月销售额增长率是多少");
            assertEquals(ReactStatus.PARTIAL,run.result().status());assertEquals("partial",run.result().coverage().get(0).status());
        }
        script=new Script(n->n==1?load("sales_summary_query"):n==2?call("sales_summary_query",Map.of()):finish("T1.current_total"));
        try(var f=new ReactFixture(script)){
            var run=f.run("上个月复购率是多少");assertNotEquals(ReactStatus.COMPLETED,run.result().status());assertEquals("missing",run.result().coverage().get(0).status());
        }
    }
}
