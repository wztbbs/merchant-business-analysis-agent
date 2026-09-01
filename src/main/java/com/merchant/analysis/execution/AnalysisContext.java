package com.merchant.analysis.execution;

import com.merchant.analysis.time.DateRange;
import com.merchant.analysis.understanding.QueryUnderstanding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次分析的共享上下文：意图、已解析的时间区间、各步骤产出（stepId → outputKey → 值），
 * 以及执行期动态调整标记（用户假设被证伪时记录）。
 */
public class AnalysisContext {

    private final QueryUnderstanding understanding;
    private final DateRange currentRange;    // 当前观察期（如上月）
    private final DateRange baselineRange;   // 对比基线期（如前月）
    private final Map<String, Map<String, Object>> stepResults = new LinkedHashMap<>();

    private boolean hypothesisBroken;
    private String hypothesisNote;
    /** 被证伪的用户假设因子（candidate_factor）；执行期据此跳过该因子的后续步骤 */
    private String falsifiedFactor;

    public AnalysisContext(QueryUnderstanding understanding, DateRange currentRange, DateRange baselineRange) {
        this.understanding = understanding;
        this.currentRange = currentRange;
        this.baselineRange = baselineRange;
    }

    public QueryUnderstanding understanding() {
        return understanding;
    }

    public DateRange current() {
        return currentRange;
    }

    public DateRange baseline() {
        return baselineRange;
    }

    public Map<String, Map<String, Object>> stepResults() {
        return stepResults;
    }

    public void putStepResult(String stepId, Map<String, Object> outputs) {
        stepResults.put(stepId, outputs);
    }

    /** 读取上游步骤的某个产出（供 inputs 引用解析） */
    public Object lookup(String stepId, String outputKey) {
        Map<String, Object> r = stepResults.get(stepId);
        return r == null ? null : r.get(outputKey);
    }

    public boolean hypothesisBroken() {
        return hypothesisBroken;
    }

    public String hypothesisNote() {
        return hypothesisNote;
    }

    /** 被证伪的用户假设因子名；未证伪返回 null */
    public String falsifiedFactor() {
        return falsifiedFactor;
    }

    public void markHypothesisBroken(String factor, String note) {
        this.hypothesisBroken = true;
        this.hypothesisNote = note;
        this.falsifiedFactor = factor;
    }
}
