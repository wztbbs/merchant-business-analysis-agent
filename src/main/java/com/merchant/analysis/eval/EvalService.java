package com.merchant.analysis.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.conclusion.ConclusionService;
import com.merchant.analysis.config.LlmConfig;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.understanding.QueryUnderstanding;
import com.merchant.analysis.understanding.QueryUnderstandingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 评测服务。
 * intent：逐条 eval_case 只调意图识别（不跑 plan/执行），逐字段比对，按字段算准确率；
 * grounding：对存量 SUCCESS 的 analysis_run 结论跑 GroundingChecker 机械校验。
 */
@Service
public class EvalService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int GROUNDING_TARGET_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final QueryUnderstandingService understandingService;
    private final ConclusionService conclusionService;
    private final EvalRepository evalRepository;
    private final RunRepository runRepository;
    private final GroundingChecker groundingChecker;
    private final LlmConfig llmConfig;

    public EvalService(QueryUnderstandingService understandingService, ConclusionService conclusionService,
                       EvalRepository evalRepository, RunRepository runRepository,
                       GroundingChecker groundingChecker, LlmConfig llmConfig) {
        this.understandingService = understandingService;
        this.conclusionService = conclusionService;
        this.evalRepository = evalRepository;
        this.runRepository = runRepository;
        this.groundingChecker = groundingChecker;
        this.llmConfig = llmConfig;
    }

    public String run(String evalType, String split) {
        return switch (evalType) {
            case "intent" -> runIntentEval(split);
            case "grounding" -> runGroundingEval();
            default -> throw new IllegalArgumentException("不支持的 eval_type: " + evalType + "（支持 intent / grounding）");
        };
    }

    /** 意图识别评测：逐字段比对，expected 为 null 的字段不参与比较 */
    private String runIntentEval(String split) {
        List<EvalRepository.EvalCase> cases = evalRepository.loadCases(split);
        if (cases.isEmpty()) {
            throw new IllegalStateException("eval_case 为空（split=" + split + "），请先准备标注集");
        }
        String evalRunId = UUID.randomUUID().toString();
        evalRepository.createEvalRun(evalRunId, "intent", llmConfig.getModel(),
                understandingService.promptVersion());

        int passed = 0;
        int callFailed = 0;                       // LLM 调用失败数：不参与考核，仅记录可见
        List<String> callErrors = new ArrayList<>();
        Map<String, int[]> fieldStats = new LinkedHashMap<>();  // 字段 → [比较次数, 通过次数]
        log.info("intent 评测开始 eval_run_id={} split={} 共 {} 条", evalRunId, split, cases.size());
        int i = 0;
        try {
            for (EvalRepository.EvalCase c : cases) {
                i++;
                log.info("[{}/{}] 意图识别: {}", i, cases.size(), c.query());
                QueryUnderstanding actual;
                try {
                    actual = understandingService.understand(c.query()).understanding();
                } catch (Exception e) {
                    // 单条 LLM 调用失败：不算通过也不算失败（不进 case_result，不计入分母），继续下一条
                    callFailed++;
                    callErrors.add(c.query() + " -> " + e.getMessage());
                    log.warn("[{}/{}] LLM 调用失败: {} -> {}", i, cases.size(), c.query(), e.getMessage());
                    continue;
                }
                log.info("[{}/{}] LLM 响应: {}", i, cases.size(), toJson(actual));
                Map<String, Integer> pass = new LinkedHashMap<>();
                compare(fieldStats, pass, "scene", c.expectedScene(), actual.primaryScene());
                compare(fieldStats, pass, "metric", c.expectedMetric(), actual.metric());
                compare(fieldStats, pass, "time_range", canonicalTime(c.expectedTimeRange()),
                        actual.time_range() == null ? null : toJson(actual.time_range()));
                compare(fieldStats, pass, "entity", c.expectedEntitySku(), actual.skuOrNull());
                compare(fieldStats, pass, "factor", c.expectedCandidateFactor(), actual.candidate_factor());

                boolean casePassed = pass.values().stream().allMatch(p -> p == null || p == 1);
                if (casePassed) {
                    passed++;
                }
                evalRepository.insertCaseResult(evalRunId, c.id(), null, toJson(actual),
                        pass.get("scene"), pass.get("metric"), pass.get("time_range"),
                        pass.get("entity"), pass.get("factor"), null,
                        casePassed, detailJson(c, actual));
            }
            Map<String, Object> metrics = new LinkedHashMap<>(metrics(fieldStats));
            if (callFailed > 0) {
                metrics.put("llm_call_failed", callFailed);
                metrics.put("llm_call_errors", callErrors);
            }
            evalRepository.finishEvalRun(evalRunId, "SUCCESS", cases.size() - callFailed, passed,
                    toJson(metrics));
            log.info("intent 评测完成 eval_run_id={} total={} passed={} llm_call_failed={}",
                    evalRunId, cases.size() - callFailed, passed, callFailed);
        } catch (Exception e) {
            evalRepository.finishEvalRun(evalRunId, "FAILED", cases.size() - callFailed, passed,
                    toJson(Map.of("error", String.valueOf(e.getMessage()))));
            throw e;
        }
        return evalRunId;
    }

    /** grounding 评测：对最近的成功分析运行做机械校验 */
    private String runGroundingEval() {
        List<EvalRepository.GroundingTarget> targets = evalRepository.loadGroundingTargets(GROUNDING_TARGET_LIMIT);
        if (targets.isEmpty()) {
            throw new IllegalStateException("没有可校验的 analysis_run（status=SUCCESS 且有结论）");
        }
        String evalRunId = UUID.randomUUID().toString();
        evalRepository.createEvalRun(evalRunId, "grounding", llmConfig.getModel(),
                conclusionService.promptVersion());

        int passed = 0;
        try {
            for (EvalRepository.GroundingTarget t : targets) {
                Map<String, Map<String, Object>> stepResults = runRepository.loadSuccessfulStepOutputs(t.runId());
                List<GroundingChecker.CheckResult> checks =
                        groundingChecker.check(t.conclusion(), stepResults, t.hypothesisBroken(), t.query(),
                                t.metric(), t.sku());
                long failCount = checks.stream().filter(c -> !c.passed()).count();
                if (failCount == 0) {
                    passed++;
                }
                evalRepository.insertCaseResult(evalRunId, null, t.runId(), t.conclusion(),
                        null, null, null, null, null, (int) failCount, failCount == 0, toJson(checks));
            }
            evalRepository.finishEvalRun(evalRunId, "SUCCESS", targets.size(), passed,
                    toJson(Map.of("pass_rate", targets.isEmpty() ? 0 : (double) passed / targets.size())));
        } catch (Exception e) {
            evalRepository.finishEvalRun(evalRunId, "FAILED", targets.size(), passed,
                    toJson(Map.of("error", String.valueOf(e.getMessage()))));
            throw e;
        }
        return evalRunId;
    }

    /** expected 为 null → 该字段不参与比较（pass 记 null，统计时忽略） */
    private static void compare(Map<String, int[]> fieldStats, Map<String, Integer> pass,
                                String field, String expected, String actual) {
        if (expected == null) {
            pass.put(field, null);
            return;
        }
        boolean ok = Objects.equals(expected, actual);
        pass.put(field, ok ? 1 : 0);
        fieldStats.computeIfAbsent(field, k -> new int[2]);
        fieldStats.get(field)[0]++;
        if (ok) {
            fieldStats.get(field)[1]++;
        }
    }

    private static String canonicalTime(String expected) {
        if (expected == null) return null;
        try {
            var spec = expected.startsWith("{")
                    ? MAPPER.readValue(expected, com.merchant.analysis.time.TimeSpec.class)
                    : com.merchant.analysis.time.TimeSpec.legacy(expected);
            return toJson(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("评测时间标注非法: " + expected, e);
        }
    }

    private static Map<String, Object> metrics(Map<String, int[]> fieldStats) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        fieldStats.forEach((field, stat) -> metrics.put(field + "_acc",
                stat[0] == 0 ? null : (double) stat[1] / stat[0]));
        return metrics;
    }

    private static String detailJson(EvalRepository.EvalCase c, QueryUnderstanding actual) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("query", c.query());
        detail.put("expected", Map.of("scene", c.expectedScene(),
                "entity_sku", c.expectedEntitySku() == null ? "" : c.expectedEntitySku(),
                "candidate_factor", c.expectedCandidateFactor() == null ? "" : c.expectedCandidateFactor()));
        detail.put("actual_scene", actual.primaryScene());
        return toJson(detail);
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("评测结果序列化失败", e);
        }
    }
}
