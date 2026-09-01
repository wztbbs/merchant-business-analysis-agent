package com.merchant.analysis.plan;

import java.util.List;

/** 完整执行计划：场景 + 有序步骤列表 */
public record Plan(String scene, List<PlanStep> steps) {

    public PlanStep step(String stepId) {
        return steps.stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("plan 中不存在步骤: " + stepId));
    }
}
