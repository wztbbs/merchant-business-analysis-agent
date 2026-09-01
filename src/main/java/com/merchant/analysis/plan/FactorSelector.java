package com.merchant.analysis.plan;

import com.merchant.analysis.playbook.Skeleton;
import com.merchant.analysis.understanding.QueryUnderstanding;

import java.util.ArrayList;
import java.util.List;

/**
 * 因子选择策略：策略由骨架 factor_slot 配置声明——
 * include_user_candidate 为 true 时用户假设因子（candidate_factor）必选且排最前；
 * default_policy = append_after_user_candidate：默认因子按声明顺序去重追加在假设因子之后。
 */
final class FactorSelector {

    private FactorSelector() {
    }

    static List<String> selectFactors(Skeleton.FactorSlot slot, QueryUnderstanding understanding) {
        List<String> factorNames = new ArrayList<>();
        if (slot.includeUserCandidate()
                && understanding.candidate_factor() != null && !understanding.candidate_factor().isBlank()) {
            factorNames.add(understanding.candidate_factor());
        }
        for (String factorName : slot.defaultFactors()) {
            if (!factorNames.contains(factorName)) {
                factorNames.add(factorName);
            }
        }
        return factorNames;
    }
}
