package com.merchant.analysis.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.eval.EvalCaseSeeder;
import com.merchant.analysis.seed.SeedDataGenerator;
import com.merchant.analysis.time.TimeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"llm.provider=mock","spring.datasource.url=jdbc:sqlite:file:react-http-test?mode=memory&cache=shared","spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
class ReactHttpTest {
    @TestConfiguration static class FixedTime {
        @Bean @Primary TimeResolver fixedTime(){return new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"),ZoneOffset.UTC),ZoneId.of("Asia/Shanghai"),null);}
    }
    @MockBean SeedDataGenerator seed;
    @MockBean EvalCaseSeeder cases;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReactSettings settings;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void data(){
        settings.setMaxModelCalls(12);
        for(String table:List.of("react_event","react_run","step_run","conversation_turn","conversation_session","analysis_run","order_item","sku_status_log","sku"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO sku VALUES('s1','无糖可乐','饮料'),('s2','巧克力','零食')");
        jdbc.update("INSERT INTO order_item(order_id,sku_id,ts,qty,amount,price) VALUES "
                +"('b1','s1','2026-09-15 12:00:00',10,100,10),('b2','s2','2026-09-15 12:00:00',10,50,5),"
                +"('c1','s1','2026-10-15 12:00:00',5,60,12),('c2','s2','2026-10-15 12:00:00',20,100,5)");
    }
    @Test void routesThroughSceneAndPersistsReviewableTrace() throws Exception {
        String response=mvc.perform(post("/api/analyze").contentType("application/json").content(json.writeValueAsString(Map.of("query",ReactServiceTest.QUERY))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.routing.matched_react_scene").value("category_structure_review"))
                .andExpect(jsonPath("$.step_results.T2.value").value("100")).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("run_id").asText();
        mvc.perform(get("/api/react/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.state.finalized").value(true)).andExpect(jsonPath("$.events").isNotEmpty());
        int events=jdbc.queryForObject("SELECT COUNT(*) FROM react_event WHERE run_id=?",Integer.class,id);
        mvc.perform(post("/api/analyze").contentType("application/json").content(json.writeValueAsString(Map.of("runId",id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals(events,jdbc.queryForObject("SELECT COUNT(*) FROM react_event WHERE run_id=?",Integer.class,id));
    }
    @Test void unmatchedSceneExecutesWhileCoveredQueryRetainsPlaybook() throws Exception {
        mvc.perform(post("/api/analyze").contentType("application/json").content("{\"query\":\"上个月无糖可乐销售额是多少\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.routing.matched_react_scene").isEmpty()).andExpect(jsonPath("$.step_results.T1.sku_total_sales").value(60));
        mvc.perform(post("/api/analyze").contentType("application/json").content("{\"query\":\"上个月销售额是多少\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.plan.scene").value("metric_query_report")).andExpect(jsonPath("$.step_results.S1.current_total").value(160));
    }
    @Test void partialRunCanBeReferencedWithoutPretendingItWasComplete() throws Exception {
        settings.setMaxModelCalls(3);
        String response=mvc.perform(post("/api/analyze").contentType("application/json").content("{\"query\":\"上个月品类销售额是多少\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BUDGET_EXCEEDED"))
                .andReturn().getResponse().getContentAsString();
        String session=json.readTree(response).path("session_id").asText();
        mvc.perform(post("/api/analyze").contentType("application/json").content(json.writeValueAsString(Map.of("query","解释刚才的结果","sessionId",session))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.routing.route").value("HISTORY"))
                .andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.source_status").value("BUDGET_EXCEEDED"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM analysis_run",Integer.class));
    }
}
