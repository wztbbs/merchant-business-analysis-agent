package com.merchant.analysis.playbook;

import java.util.List;

/**
 * 场景骨架：固定步骤 + 因子插槽 + 收尾步。
 */
public record Skeleton(
        String scene,
        List<StepTemplate> fixedSteps,
        FactorSlot factorSlot,         // 因子插槽；null 表示无插槽
        StepTemplate finalStep) {

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
