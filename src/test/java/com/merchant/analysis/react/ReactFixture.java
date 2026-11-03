package com.merchant.analysis.react;

import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.eval.GroundingChecker;
import com.merchant.analysis.execution.*;
import com.merchant.analysis.execution.executors.*;
import com.merchant.analysis.llm.*;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.Plan;
import com.merchant.analysis.scene.ReactSceneCatalog;
import com.merchant.analysis.time.*;
import com.merchant.analysis.tool.*;
import com.merchant.analysis.understanding.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

final class ReactFixture implements AutoCloseable {
    final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    final JdbcTemplate jdbc = new JdbcTemplate(ds);
    final RunRepository runs = new RunRepository(jdbc);
    final ReactRepository repository = new ReactRepository(jdbc);
    final BoundedCalls bounded = new BoundedCalls();
    final ReactSettings settings;
    final ReactService service;
    final TimeResolver time = new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
    final QueryUnderstandingService understanding;
    record Run(String id, QueryUnderstanding understanding, ReactResult result) {}
    ReactFixture(LlmClient llm) { this(llm, new ReactSettings(), Map.of(), new CalculationEngine()); }
    ReactFixture(LlmClient llm, ReactSettings settings, Map<String, CapabilityExecutor> overrides, CalculationEngine calculations) {
        this.settings=settings;
        new ResourceDatabasePopulator(new ClassPathResource("db/tables.sql")).execute(ds);
        jdbc.update("INSERT INTO sku VALUES('s1','无糖可乐','饮料'),('s2','巧克力','零食')");
        jdbc.update("INSERT INTO order_item(order_id,sku_id,ts,qty,amount,price) VALUES "
                + "('b1','s1','2026-09-15 12:00:00',10,100,10),('b2','s2','2026-09-15 12:00:00',10,50,5),"
                + "('c1','s1','2026-10-15 12:00:00',5,60,12),('c2','s2','2026-10-15 12:00:00',20,100,5)");
        jdbc.update("INSERT INTO sku_status_log(sku_id,event_type,event_time) VALUES "
                + "('s1','listing','2026-09-01 00:00:00'),('s1','stockout_start','2026-10-05 00:00:00'),('s1','stockout_end','2026-10-06 00:00:00')");
        Map<String,CapabilityExecutor> executors=new HashMap<>();
        executors.put("salesSummaryQueryExecutor",new SalesSummaryQueryExecutor(jdbc));
        executors.put("skuSalesBreakdownExecutor",new SkuSalesBreakdownExecutor(jdbc));
        executors.put("categorySalesBreakdownExecutor",new CategorySalesBreakdownExecutor(jdbc));
        executors.put("skuStatusQueryExecutor",new SkuStatusQueryExecutor(jdbc));
        executors.put("skuSalesHistoryExecutor",new SkuSalesHistoryExecutor(jdbc));
        executors.put("substituteSkuAnalysisExecutor",new SubstituteSkuAnalysisExecutor(jdbc));
        executors.put("campaignQueryExecutor",new CampaignQueryExecutor(jdbc));
        executors.put("trafficConversionQueryExecutor",new TrafficConversionQueryExecutor(jdbc));
        executors.put("priceChangeQueryExecutor",new PriceChangeQueryExecutor(jdbc));
        executors.put("stockoutQueryExecutor",new StockoutQueryExecutor(jdbc));
        executors.putAll(overrides);
        var catalog=new CapabilityCatalog();var scenes=new ReactSceneCatalog(catalog);
        var runtime=new ToolRuntime(new ToolCatalog(catalog),catalog,executors,calculations,time);
        understanding=new QueryUnderstandingService(new MockLlmClient(),new ClassPathResource("prompts/query_understanding.txt"),time);
        service=new ReactService(runtime,scenes,llm,settings,bounded,repository,runs,new ReactCompletion(time),
                new ReactAnswers(new ClassPathResource("prompts/react_answer.txt"),new GroundingChecker()),new ClassPathResource("prompts/react_decision.txt"));
    }
    Run run(String query) {
        QueryUnderstanding u=understanding.understand(query).understanding();
        String id=create(u);
        return new Run(id,u,service.execute(id,u,List.of()));
    }
    String create(QueryUnderstanding u) { return runs.createRun(u.task().original_query(),new Plan("react",List.of()),u,null); }
    static ReactSettings fastSettings() {
        var s=new ReactSettings();s.setTotalTimeoutMs(3000);s.setAnswerReserveMs(300);s.setModelTimeoutMs(500);s.setToolTimeoutMs(100);return s;
    }
    static final class Script implements LlmClient {
        final AtomicInteger decisions=new AtomicInteger(), calls=new AtomicInteger();
        final IntFunction<String> action;
        String answer="本次分析依据已有数据结束，未完成部分请参阅完成情况。";
        Script(IntFunction<String> action){this.action=action;}
        public String chat(String prompt){calls.incrementAndGet();return prompt.startsWith("TEMPLATE: react_answer")?answer:action.apply(decisions.incrementAndGet());}
    }
    static String load(String...names){return ReactViews.json(Map.of("action","load_tools","names",List.of(names)));}
    static String call(String name,Map<String,Object>args){return ReactViews.json(Map.of("action","call_tool","tool",name,"arguments",args));}
    static String finish(String...evidence){return ReactViews.json(Map.of("action","finish","status","COMPLETED","reason","已取得结果", "requirements",List.of(Map.of("index",0,"status","completed","evidence",List.of(evidence),"reason","查询结果"))));}
    @Override public void close(){bounded.close();ds.destroy();}
}
