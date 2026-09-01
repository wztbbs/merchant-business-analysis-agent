package com.merchant.analysis.api;

import com.merchant.analysis.conclusion.ConclusionService;
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

    /** 兜底话术：问题超出支持范围时直接返回，不走 plan 链路 */
    private static final String OUT_OF_SCOPE_REPLY = """
            这个问题超出了我目前的能力范围。我目前支持三类经营分析问题：
            1. 指标变化诊断，如「为什么我上个月销售额下滑了」；
            2. 因子影响评估，如「我把无糖可乐下架了影响大吗」；
            3. 指标查询/报表，如「上个月销售额是多少」。
            可以换个问法试试。""";

    public AnalyzeController(QueryUnderstandingService understandingService, PlanWeaver planWeaver,
                             PlanValidator planValidator, ExecutionService executionService,
                             ConclusionService conclusionService, RunRepository runRepository,
                             BadQueryCaseRepository badQueryCaseRepository,
                             ConversationService conversationService) {
        this.understandingService = understandingService;
        this.planWeaver = planWeaver;
        this.planValidator = planValidator;
        this.executionService = executionService;
        this.conclusionService = conclusionService;
        this.runRepository = runRepository;
        this.badQueryCaseRepository = badQueryCaseRepository;
        this.conversationService = conversationService;
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
        // 校验兜底：LLM 判了追问但 ref_run_id 不在候选链里（或为空）→ 降级为新问题
        String refRunId = result.refRunId();
        if (result.followUp() && (refRunId == null
                || chains.stream().noneMatch(c -> c.runId().equals(refRunId)))) {
            result = UnderstandingResult.fresh(result.understanding());
        }
        // conv 为空时结果必然已被兜底为非追问（候选链为空），直接开新会话
        SessionRecord session = conv == null
                ? conversationService.createSession(request.merchantId(), request.userId(), query)
                : conversationService.settle(conv, result, query);

        // 读历史型追问：不新跑 plan，引用被承接 run 的结论与步骤数据回答
        if (result.followUp() && !result.needsRerun()) {
            return answerFromHistory(session, result, query);
        }

        QueryUnderstanding understanding = result.understanding();

        // 兜底：意图识别判定超出支持范围（out_of_scope）时，不进 plan/执行链路，直接返回能力说明；
        // 同时落库 bad_query_case，按 (suggest_action, suggest_object) 聚合统计，驱动新场景支持优先级
        if ("out_of_scope".equals(understanding.primaryScene())) {
            badQueryCaseRepository.save(query, understanding);
            String turnId = conversationService.recordStandaloneTurn(session, result, query, OUT_OF_SCOPE_REPLY);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("session_id", session.sessionId());
            r.put("turn_id", turnId);
            r.put("understanding", understanding);
            r.put("plan", null);
            r.put("step_results", Map.of());
            r.put("hypothesis_broken", false);
            r.put("conclusion", OUT_OF_SCOPE_REPLY);
            return r;
        }

        Plan plan = planWeaver.weave(understanding);
        planValidator.validate(plan);

        // run chain：追问重跑的新 run 挂到被承接链的链根下
        String rootRunId = result.followUp() ? rootOf(result.refRunId(), chains) : null;
        String runId = runRepository.createRun(query, plan, understanding, rootRunId);
        try {
            AnalysisContext ctx = executionService.execute(plan, understanding, runId);
            String conclusion = conclusionService.conclude(query, plan, ctx);
            runRepository.finishRun(runId, "SUCCESS", ctx.hypothesisBroken(), conclusion, null);
            String turnId = conversationService.recordRunTurn(session, result, query, null, runId);
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

    /** 读历史型追问：引用被承接 run（ref_run_id 可追溯回答依据），不重跑 plan */
    private Map<String, Object> answerFromHistory(SessionRecord session, UnderstandingResult result,
                                                  String query) {
        RunRepository.RunRecord refRun = runRepository.findRun(result.refRunId());
        String answer;
        Map<String, Map<String, Object>> stepResults = Map.of();
        if (refRun == null || !"SUCCESS".equals(refRun.status())) {
            answer = "没有找到可引用的历史分析结果，请重新提问。";
        } else {
            stepResults = runRepository.loadSuccessfulStepOutputs(refRun.runId());
            answer = conclusionService.answerFollowUp(query, result.followUpType(), refRun.query(),
                    refRun.conclusion(), stepResults);
        }
        String turnId = conversationService.recordStandaloneTurn(session, result, query, answer);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("session_id", session.sessionId());
        r.put("turn_id", turnId);
        r.put("is_follow_up", true);
        r.put("follow_up_type", result.followUpType());
        r.put("ref_run_id", result.refRunId());
        r.put("understanding", null);
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

    private static Map<String, Object> buildResponse(String runId, QueryUnderstanding understanding, Plan plan,
                                                     Map<String, Map<String, Object>> stepResults,
                                                     boolean hypothesisBroken, String conclusion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", runId);
        result.put("understanding", understanding);
        result.put("plan", plan);
        result.put("step_results", stepResults);
        result.put("hypothesis_broken", hypothesisBroken);
        result.put("conclusion", conclusion);
        return result;
    }
}
