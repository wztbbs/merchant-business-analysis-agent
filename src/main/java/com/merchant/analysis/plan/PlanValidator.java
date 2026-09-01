package com.merchant.analysis.plan;

import com.merchant.analysis.exception.PlanValidationException;
import com.merchant.analysis.execution.CapabilityCatalog;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plan 确定性校验（不调 LLM）：
 * - 结构校验：步骤 id 唯一、depends_on 存在且无环、inputs 能在上游步骤 outputs 中找到；
 * - 供给校验：capability 在能力目录白名单内、声明的 output 在其 produces 中。
 * 任一失败抛 {@link PlanValidationException}。
 */
@Service
public class PlanValidator {

    private final CapabilityCatalog catalog;

    public PlanValidator(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    public void validate(Plan plan) {
        Map<String, PlanStep> byId = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            if (step.stepId() == null || step.stepId().isBlank()) {
                throw new PlanValidationException("存在缺少 step_id 的步骤");
            }
            if (byId.put(step.stepId(), step) != null) {
                throw new PlanValidationException("步骤 id 重复: " + step.stepId());
            }
        }

        for (PlanStep step : plan.steps()) {
            // 依赖存在性
            for (String dep : step.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new PlanValidationException(step.stepId() + " depends_on 引用了不存在的步骤: " + dep);
                }
            }
            // 供给校验
            validateSupply(step);
            // inputs 闭合：引用的上游步骤已产出对应 key
            for (Map.Entry<String, String> input : step.inputs().entrySet()) {
                String ref = input.getValue();
                int dot = ref == null ? -1 : ref.indexOf('.');
                if (dot <= 0) {
                    throw new PlanValidationException(step.stepId() + " 的 input " + input.getKey() + " 引用形态非法: " + ref);
                }
                PlanStep upstream = byId.get(ref.substring(0, dot));
                String key = ref.substring(dot + 1);
                if (upstream == null) {
                    throw new PlanValidationException(step.stepId() + " 的 input " + input.getKey() + " 引用了不存在的步骤: " + ref);
                }
                if (!upstream.outputs().contains(key)) {
                    throw new PlanValidationException(step.stepId() + " 的 input " + input.getKey()
                            + " 引用的 " + ref + " 不在上游步骤 outputs 中: " + upstream.outputs());
                }
            }
        }

        detectCycle(plan);
    }

    private void validateSupply(PlanStep step) {
        Set<String> produced = new HashSet<>();
        for (String cap : step.capabilities()) {
            if (!catalog.exists(cap)) {
                throw new PlanValidationException(step.stepId() + " 引用了能力目录之外的 capability: " + cap);
            }
            produced.addAll(catalog.get(cap).produces());
        }
        for (String out : step.outputs()) {
            if (!produced.contains(out)) {
                throw new PlanValidationException(step.stepId() + " 声明的 output " + out
                        + " 不在其能力 " + step.capabilities() + " 的 produces 中");
            }
        }
    }

    /** 三色 DFS 判环 */
    private void detectCycle(Plan plan) {
        Map<String, Integer> state = new HashMap<>(); // 0=未访问 1=访问中 2=已完成
        for (PlanStep step : plan.steps()) {
            dfs(step.stepId(), plan, state);
        }
    }

    private void dfs(String id, Plan plan, Map<String, Integer> state) {
        int s = state.getOrDefault(id, 0);
        if (s == 1) {
            throw new PlanValidationException("depends_on 存在环，涉及步骤: " + id);
        }
        if (s == 2) {
            return;
        }
        state.put(id, 1);
        for (String dep : plan.step(id).dependsOn()) {
            dfs(dep, plan, state);
        }
        state.put(id, 2);
    }
}
