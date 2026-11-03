package com.merchant.analysis.playbook;

import java.util.List;

/**
 * 场景骨架：固定步骤 + 因子插槽 + 收尾步。
 */
public record Skeleton(
        String scene,
        List<StepTemplate> fixedSteps,
        FactorSlot factorSlot,         // 因子插槽；null 表示无插槽
        StepTemplate finalStep,
        String description,
        Coverage coverage) {

    public record Coverage(List<String> actions, List<String> metrics, List<String> scopes,
                           List<String> candidate_factors, boolean requires_sku, int max_requirements) {
        public Coverage {
            actions = List.copyOf(actions); metrics = List.copyOf(metrics); scopes = List.copyOf(scopes);
            candidate_factors = List.copyOf(candidate_factors);
            if (actions.isEmpty() || metrics.isEmpty() || scopes.isEmpty() || max_requirements < 1)
                throw new IllegalArgumentException("模板覆盖契约需要动作、指标、范围和正整数需求数上限");
        }
    }

    /**
     * 因子插槽：因子选择策略。
     * includeUserCandidate：用户假设因子（candidate_factor）是否必选；
     * defaultPolicy：默认因子如何并入，当前仅支持 append_after_user_candidate（去重追加在假设因子之后）。
     */
    public record FactorSlot(
            List<String> defaultFactors,
            boolean includeUserCandidate,
            String defaultPolicy) {
    }
}
