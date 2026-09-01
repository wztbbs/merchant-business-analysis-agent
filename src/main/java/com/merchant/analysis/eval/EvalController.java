package com.merchant.analysis.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测入口：
 * POST /api/eval/run {"evalType": "intent", "split": "dev"}  —— 触发评测（同步），直接返回完整结果
 * GET  /api/eval/query/{evalRunId}                           —— 按 eval_run_id 查询单批次结果（含逐 case 明细）
 * GET  /api/eval/stats?evalType=intent                       —— 跨批次聚合统计
 * PUT  /api/eval/case [{query, expectedScene, ...}]          —— label 批量提交（按 query 逐条 upsert）
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    public record EvalRunRequest(String evalType, String split) {
    }

    /** label 提交项：query 必填，expectedScene 必填，其余可空（空表示该字段不参与比较） */
    public record EvalCaseInput(String query, String expectedScene, String expectedMetric,
                                String expectedTimeRange, String expectedEntitySku,
                                String expectedCandidateFactor, String split, String source) {
    }

    private final EvalService evalService;
    private final EvalRepository evalRepository;

    public EvalController(EvalService evalService, EvalRepository evalRepository) {
        this.evalService = evalService;
        this.evalRepository = evalRepository;
    }

    /** 触发评测：同步执行完毕，响应与 query 接口同构（eval_run + case_results），附 eval_run_id 供事后查询 */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody EvalRunRequest request) {
        String evalRunId = evalService.run(request.evalType(), request.split());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eval_run_id", evalRunId);
        result.putAll(query(evalRunId));
        return result;
    }

    /** 查询单批次评测结果（含逐 case 明细） */
    @GetMapping("/query/{evalRunId}")
    public Map<String, Object> query(@PathVariable String evalRunId) {
        Map<String, Object> run = evalRepository.findEvalRun(evalRunId);
        if (run == null) {
            throw new IllegalArgumentException("eval_run 不存在: " + evalRunId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eval_run", run);
        result.put("case_results", evalRepository.listCaseResults(evalRunId));
        return result;
    }

    @GetMapping("/stats")
    public List<Map<String, Object>> stats(@RequestParam(required = false) String evalType) {
        return evalRepository.stats(evalType);
    }

    /**
     * label 批量提交（PUT /api/eval/case）：逐条 upsert——query 已存在则更新，否则插入。
     * 单条失败不影响其他条目，失败明细在响应 failed 列表中返回。
     */
    @PutMapping("/case")
    public Map<String, Object> upsertCases(@RequestBody List<EvalCaseInput> cases) {
        int inserted = 0;
        int updated = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (EvalCaseInput c : cases) {
            try {
                if (c.query() == null || c.query().isBlank() || c.expectedScene() == null) {
                    throw new IllegalArgumentException("query 和 expectedScene 必填");
                }
                String result = evalRepository.upsertCase(c.query(), c.expectedScene(), c.expectedMetric(),
                        c.expectedTimeRange(), c.expectedEntitySku(), c.expectedCandidateFactor(),
                        c.split(), c.source());
                if ("inserted".equals(result)) {
                    inserted++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                failed.add(Map.of("query", String.valueOf(c.query()), "error", String.valueOf(e.getMessage())));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", cases.size());
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("failed", failed);
        return result;
    }
}
