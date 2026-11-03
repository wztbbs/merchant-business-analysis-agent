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
            step.inputs().values().forEach(reference -> validateReference(step, reference, byId));
            validateInlineReferences(step, step.params(), byId);

        }

        detectCycle(plan);
    }

    private void validateInlineReferences(PlanStep step, Object value, Map<String, PlanStep> byId) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$ref")) {
                if (map.size() != 1 || !(map.get("$ref") instanceof String))
                    throw new PlanValidationException("非法结果引用对象: " + step.stepId());
                validateReference(step, (String) map.get("$ref"), byId);
            } else map.values().forEach(child -> validateInlineReferences(step, child, byId));
        } else if (value instanceof java.util.List<?> list) {
            list.forEach(child -> validateInlineReferences(step, child, byId));
        }
    }

    private void validateReference(PlanStep step, String reference, Map<String, PlanStep> byId) {
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_]*(\\.(?:[A-Za-z_][A-Za-z0-9_]*|[0-9]+))+"))
            throw new PlanValidationException(step.stepId() + " 的结果引用形态非法: " + reference);
        String[] parts = reference.split("\\.");
        PlanStep upstream = byId.get(parts[0]);
        if (upstream == null || !upstream.outputs().contains(parts[1]))
            throw new PlanValidationException(step.stepId() + " 引用了未声明的上游产出: " + reference);
        Set<String> ancestors = new HashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(step.dependsOn());
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (ancestors.add(id) && byId.containsKey(id)) pending.addAll(byId.get(id).dependsOn());
        }
        if (step.stepId().equals(upstream.stepId()) || !ancestors.contains(upstream.stepId()))
            throw new PlanValidationException(step.stepId() + " 引用结果但未声明上游依赖: " + reference);
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
