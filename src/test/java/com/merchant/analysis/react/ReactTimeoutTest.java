package com.merchant.analysis.react;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.llm.LlmClient;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.merchant.analysis.react.ReactFixture.*;

class ReactTimeoutTest {
    @Test void timedOutToolCannotPublishAfterCallerHasReturned() throws Exception {
        CountDownLatch release=new CountDownLatch(1),finished=new CountDownLatch(1);
        CapabilityExecutor slow=new CapabilityExecutor(){
            public String name(){return "stockout_query";}
            public Map<String,Object> execute(Map<String,Object> args,AnalysisContext ctx){
                try { while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){} }
                finally{finished.countDown();}
                return Map.of("stockout_events",List.of(),"comment","迟到结果");
            }
        };
        var settings=fastSettings();settings.setToolTimeoutMs(30);
        var script=new Script(n->n==1?load("stockout_query"):call("stockout_query",Map.of()));
        try(var f=new ReactFixture(script,settings,Map.of("stockoutQueryExecutor",slow),new CalculationEngine())){
            try {
                var run=f.run("上个月缺货事件有哪些");
                assertEquals(ReactStatus.BUDGET_EXCEEDED,run.result().status());
                assertTrue(run.result().stepResults().isEmpty());
                release.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS));
                assertTrue(f.runs.loadSuccessfulStepOutputs(run.id()).isEmpty());
                assertEquals("FAILED",f.jdbc.queryForObject("SELECT status FROM step_run WHERE run_id=? AND step_id='T1'",String.class,run.id()));
                int before=script.calls.get();f.service.resume(run.id(),run.understanding());assertEquals(before,script.calls.get());
            }finally{release.countDown();finished.await(1,TimeUnit.SECONDS);}
        }
    }
    @Test void timedOutModelDoesNotBlockFallbackOrChangePersistedStateLater() throws Exception {
        CountDownLatch release=new CountDownLatch(1),finished=new CountDownLatch(1);
        LlmClient slow=prompt->{
            if(prompt.startsWith("TEMPLATE: react_answer"))return "本次分析未完成，没有可用的数值结论。";
            try{while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}}
            finally{finished.countDown();}
            return load("sum");
        };
        var settings=fastSettings();settings.setModelTimeoutMs(30);
        try(var f=new ReactFixture(slow,settings,Map.of(),new CalculationEngine())){
            try{
                var run=f.run(ReactServiceTest.QUERY);
                assertEquals(ReactStatus.BUDGET_EXCEEDED,run.result().status());
                assertEquals(2,run.result().stats().get("model_calls"));
                release.countDown();assertTrue(finished.await(1,TimeUnit.SECONDS));
                assertTrue(f.repository.load(run.id()).loaded.isEmpty());
                assertTrue(f.repository.load(run.id()).finalized);
            }finally{release.countDown();finished.await(1,TimeUnit.SECONDS);}
        }
    }    @Test void simultaneousResumeDoesNotStartAnotherModelLoop() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var decisions=new java.util.concurrent.atomic.AtomicInteger();
        LlmClient client=prompt->{
            if(prompt.startsWith("TEMPLATE: react_answer"))return "本次分析未全部完成。";
            decisions.incrementAndGet(); entered.countDown();
            try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            return finish();
        };
        var settings=fastSettings();settings.setModelTimeoutMs(2000);
        var executor=Executors.newSingleThreadExecutor();
        try(var f=new ReactFixture(client,settings,Map.of(),new CalculationEngine())){
            var u=f.understanding.understand(ReactServiceTest.QUERY).understanding();String id=f.create(u);
            var first=executor.submit(()->f.service.execute(id,u,List.of()));
            try {
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                assertEquals(ReactStatus.RUNNING,f.service.resume(id,u).status());
                assertEquals(1,decisions.get());
                release.countDown(); first.get(3,TimeUnit.SECONDS);
            }finally{release.countDown();executor.shutdownNow();}
        }finally{release.countDown();executor.shutdownNow();}
    }

}
