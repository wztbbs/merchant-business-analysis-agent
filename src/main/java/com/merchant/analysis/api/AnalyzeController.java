package com.merchant.analysis.api;

import com.merchant.analysis.conclusion.ConclusionService;
import com.merchant.analysis.react.*;
import com.merchant.analysis.conversation.ConversationService;
import com.merchant.analysis.conversation.SessionRecord;
import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.ExecutionService;
import com.merchant.analysis.persistence.BadQueryCaseRepository;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.Plan;
import com.merchant.analysis.plan.PlanValidator;
import com.merchant.analysis.plan.PlanWeaver;
import com.merchant.analysis.understanding.ChainContext;
import com.merchant.analysis.understanding.QueryUnderstanding;
import com.merchant.analysis.understanding.QueryUnderstandingService;
import com.merchant.analysis.understanding.UnderstandingResult;
import com.merchant.analysis.understanding.RoutingDecision;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分析入口：POST /api/analyze {"query": "..."} 或 {"runId": "..."}
 * 新分析链路：意图识别 → plan 生成 → plan 校验 → 执行 → 结论生成，全链路结果一次返回。
 * runId 非空表示重试：从 analysis_run/step_run 恢复执行状态，断点续跑未完成步骤。
 *
 * 会话与追问：请求可带 merchantId/userId/sessionId。入口先取候选 run chain 注入意图识别，
 * LLM 一次调用完成「意图识别 + 追问判定 + 追问组装」：
 * - 追问且 needs_rerun=false（factor_detail/advice/explain）：不新跑 plan，引用被承接 run 的结果回答；
 * - 追问且 needs_rerun=true（metric_switch/time_compare）：组装后的意图走正常链路重跑，
 *   新 run 通过 root_run_id 挂到原话题线（run chain）；
 * - 非追问：开新会话，正常链路。
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final QueryUnderstandingService understandingService;
    private final PlanWeaver planWeaver;
    private final PlanValidator planValidator;
    private final ExecutionService executionService;
    private final ConclusionService conclusionService;
    private final RunRepository runRepository;
    private final BadQueryCaseRepository badQueryCaseRepository;
    private final ConversationService conversationService;
    private final ReactService react;

    /** 兜底话术：问题超出支持范围时直接返回，不走 plan 链路 */
    private static final String OUT_OF_SCOPE_REPLY = "我可以帮助分析店铺经营数据，请提出与店铺经营相关的问题。";

    public AnalyzeController(QueryUnderstandingService understandingService, PlanWeaver planWeaver,
                             PlanValidator planValidator, ExecutionService executionService,
                             ConclusionService conclusionService, RunRepository runRepository,
                             BadQueryCaseRepository badQueryCaseRepository,
                             ConversationService conversationService, ReactService react) {
        this.understandingService = understandingService;
        this.planWeaver = planWeaver;
        this.planValidator = planValidator;
        this.executionService = executionService;
        this.conclusionService = conclusionService;
        this.runRepository = runRepository;
        this.badQueryCaseRepository = badQueryCaseRepository;
        this.conversationService = conversationService;
        this.react = react;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest request) {
        if (request.runId() != null && !request.runId().isBlank()) {
            return resume(request.runId());
        }

        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }

        ConversationService.CandidateContext conv = conversationService.prepareContext(
                request.merchantId(), request.userId(), request.sessionId());
        // 无候选上下文（首次访问 / 无活跃会话）：链为空，按全新问题处理
        List<ChainContext> chains = conv == null ? List.of() : conv.chains();
        UnderstandingResult result = understandingService.understand(query, chains);
        // conv 为空时结果必然已被兜底为非追问（候选链为空），直接开新会话
        SessionRecord session = conv == null
                ? conversationService.createSession(request.merchantId(), request.userId(), query)
                : conversationService.settle(conv, result, query);

        QueryUnderstanding understanding = result.understanding();
        RoutingDecision.Mode route = understanding.routing().route();
        if (route == RoutingDecision.Mode.CLARIFY)
            return standalone(session, result, query, "NEEDS_CLARIFICATION", understanding.task().clarification_question());
        if (route == RoutingDecision.Mode.OUT_OF_DOMAIN) {
            badQueryCaseRepository.save(query, understanding);
            return standalone(session, result, query, "OUT_OF_DOMAIN", OUT_OF_SCOPE_REPLY);
        }
        if (route == RoutingDecision.Mode.REACT) {
            String rootRunId = result.followUp() ? rootOf(result.refRunId(), chains) : null;
            Plan plan = new Plan("react", List.of());
            String runId = runRepository.createRun(query, plan, understanding, rootRunId);
            try {
                ReactResult output = react.execute(runId, understanding, chains);
                String turnId = conversationService.recordRunTurn(session, result, query, understanding.task().resolved_query(), runId);
                Map<String, Object> response = reactResponse(runId, understanding, plan, output);
                response.put("session_id", session.sessionId()); response.put("turn_id", turnId);
                response.put("is_follow_up", result.followUp()); response.put("follow_up_type", result.followUpType());
                response.put("ref_run_id", result.refRunId());
                if (output.status() == ReactStatus.CAPABILITY_MISSING) badQueryCaseRepository.save(query, understanding);
                return response;
            } catch (Exception e) {
                runRepository.finishRun(runId, "FAILED", false, null, String.valueOf(e.getMessage()));
                throw e;
            }
        }
        if (route == RoutingDecision.Mode.HISTORY) return answerFromHistory(session, result, query);

        Plan plan = planWeaver.weave(understanding);
        planValidator.validate(plan);

        // run chain：追问重跑的新 run 挂到被承接链的链根下
        String rootRunId = result.followUp() ? rootOf(result.refRunId(), chains) : null;
        String runId = runRepository.createRun(query, plan, understanding, rootRunId);
        try {
            AnalysisContext ctx = executionService.execute(plan, understanding, runId);
            String conclusion = conclusionService.conclude(query, plan, ctx);
            runRepository.finishRun(runId, "SUCCESS", ctx.hypothesisBroken(), conclusion, null);
            String turnId = conversationService.recordRunTurn(session, result, query, understanding.task().resolved_query(), runId);
            Map<String, Object> r = buildResponse(runId, understanding, plan, ctx.stepResults(),
                    ctx.hypothesisBroken(), conclusion);
            r.put("session_id", session.sessionId());
            r.put("turn_id", turnId);
            r.put("is_follow_up", result.followUp());
            r.put("follow_up_type", result.followUpType());
            r.put("ref_run_id", result.refRunId());
            return r;
        } catch (Exception e) {
            runRepository.finishRun(runId, "FAILED", false, null, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    private Map<String, Object> standalone(SessionRecord session, UnderstandingResult result, String query,
                                           String status, String answer) {
        String turnId = conversationService.recordStandaloneTurn(session, result, query, answer);
        Map<String, Object> response = buildResponse(null, result.understanding(), null, Map.of(), false, answer);
        response.put("status", status);
        response.put("session_id", session.sessionId()); response.put("turn_id", turnId);
        response.put("is_follow_up", result.followUp()); response.put("follow_up_type", result.followUpType());
        response.put("ref_run_id", result.refRunId());
        return response;
    }

    /** 读历史型追问：引用被承接 run（ref_run_id 可追溯回答依据），不重跑 plan */
    private Map<String, Object> answerFromHistory(SessionRecord session, UnderstandingResult result,
                                                  String query) {
        RunRepository.RunRecord refRun = runRepository.findRun(result.refRunId());
        String answer;
        boolean available = refRun != null && RunRepository.readable(refRun.status());
        Map<String, Map<String, Object>> stepResults = Map.of();
        if (!available) {
            answer = "没有找到可引用的历史分析结果，请重新提问。";
        } else {
            stepResults = runRepository.loadSuccessfulStepOutputs(refRun.runId());
            answer = conclusionService.answerFollowUp(query, result.understanding().task().resolved_query(), result.followUpType(), refRun.query(),
                    ("SUCCESS".equals(refRun.status()) ? "上次分析已完成。" : "上次分析未全部完成，只能引用已取得的事实。") + "\n" + refRun.conclusion(), stepResults);
        }
        String turnId = conversationService.recordStandaloneTurn(session, result, query, answer);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("session_id", session.sessionId());
        r.put("turn_id", turnId);
        r.put("is_follow_up", true);
        r.put("follow_up_type", result.followUpType());
        r.put("ref_run_id", result.refRunId());
        r.put("understanding", result.understanding());
        r.put("routing", result.understanding().routing());
        r.put("original_query", query);
        r.put("resolved_query", result.understanding().task().resolved_query());
        r.put("status", available ? "SUCCESS".equals(refRun.status()) ? "SUCCESS" : "PARTIAL" : "HISTORY_UNAVAILABLE");
        r.put("source_status", refRun == null ? null : refRun.status());
        r.put("plan", null);
        r.put("step_results", stepResults);
        r.put("hypothesis_broken", false);
        r.put("conclusion", answer);
        return r;
    }

    private static String rootOf(String refRunId, List<ChainContext> chains) {
        return chains.stream()
                .filter(c -> c.runId().equals(refRunId))
                .map(ChainContext::rootRunId)
                .findFirst()
                .orElse(refRunId);
    }

    /**
     * 重试：恢复 run 的执行状态。
     * 已成功 → 直接返回缓存结果（幂等）；未完成 → 断点续跑（SUCCESS 步不重跑），跑完重新生成结论。
     */
    private Map<String, Object> resume(String runId) {
        RunRepository.RunRecord run = runRepository.findRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        Plan plan = RunRepository.planFromJson(run.planJson());
        QueryUnderstanding understanding = RunRepository.understandingFromJson(run.understandingJson());
        if ("react".equals(run.scene())) return reactResponse(runId, understanding, plan, react.resume(runId, understanding));

        if ("SUCCESS".equals(run.status())) {
            return buildResponse(runId, understanding, plan, runRepository.loadSuccessfulStepOutputs(runId),
                    run.hypothesisBroken(), run.conclusion());
        }

        runRepository.markRunRunning(runId);
        try {
            AnalysisContext ctx = executionService.resume(runId, plan, understanding);
            String conclusion = conclusionService.conclude(run.query(), plan, ctx);
            runRepository.finishRun(runId, "SUCCESS", ctx.hypothesisBroken(), conclusion, null);
            return buildResponse(runId, understanding, plan, ctx.stepResults(), ctx.hypothesisBroken(), conclusion);
        } catch (Exception e) {
            runRepository.finishRun(runId, "FAILED", run.hypothesisBroken(), null, String.valueOf(e.getMessage()));
            throw e;
        }
    }

    private static Map<String, Object> reactResponse(String runId, QueryUnderstanding understanding, Plan plan, ReactResult output) {
        Map<String, Object> response = buildResponse(runId, understanding, plan, output.stepResults(), false, output.answer());
        response.put("status", output.status()); response.put("coverage", output.coverage()); response.put("react_stats", output.stats());
        response.put("trace_url", "/api/react/" + runId);
        return response;
    }

    private static Map<String, Object> buildResponse(String runId, QueryUnderstanding understanding, Plan plan,
                                                     Map<String, Map<String, Object>> stepResults,
                                                     boolean hypothesisBroken, String conclusion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        result.put("status", "SUCCESS");
        result.put("routing", understanding.routing());
        result.put("original_query", understanding.task() == null ? null : understanding.task().original_query());
        result.put("resolved_query", understanding.task() == null ? null : understanding.task().resolved_query());
        result.put("understanding", understanding);
        result.put("plan", plan);
        result.put("step_results", stepResults);
        result.put("hypothesis_broken", hypothesisBroken);
        result.put("conclusion", conclusion);
        return result;
    }
}
