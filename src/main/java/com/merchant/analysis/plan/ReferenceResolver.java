package com.merchant.analysis.plan;

import com.merchant.analysis.exception.PlanValidationException;
import com.merchant.analysis.playbook.StepTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 引用解析器：把步骤定义里的引用统一重写为运行时形式——
 * depends_on 的模块内定义 id、inputs 的 "步骤id.产出字段" 与语义引用 @产出字段，
 * 并给出 inputs 携带的数据依赖（被引用的生产者步骤）。
 */
final class ReferenceResolver {

    private ReferenceResolver() {
    }

    /** inputs 解析结果：重写后的 input 引用 + 语义引用反查到的数据依赖（生产者步骤 id） */
    record ResolvedInputs(Map<String, String> inputRefs, List<String> dataDependencies) {
    }

    /** depends_on 重映射：本组定义 id 改写为运行时 id，其余尝试全局解析（如因子引用骨架固定步骤），都不命中保持原样 */
    static List<String> resolveDependsOn(StepTemplate stepDefinition,
                                         Map<String, String> runtimeIdByDefinitionId,
                                         PlanWeavingContext context) {
        return stepDefinition.dependsOn().stream()
                .map(dep -> resolveStepRef(dep, runtimeIdByDefinitionId, context))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * inputs 重映射：普通引用 "步骤id.产出字段" 改写步骤 id（本组优先，其次全局）；
     * 语义引用 "@产出字段" 在已编织步骤中反查生产者，重写为 "运行时id.产出字段"，
     * 同时把生产者步骤记入数据依赖。
     */
    static ResolvedInputs resolveInputs(StepTemplate stepDefinition,
                                        Map<String, String> runtimeIdByDefinitionId,
                                        PlanWeavingContext context) {
        Map<String, String> inputRefs = new LinkedHashMap<>();
        List<String> dataDependencies = new ArrayList<>();
        stepDefinition.inputs().forEach((inputName, ref) -> {
            if (ref.startsWith("@")) {
                String outputName = ref.substring(1);
                String producerStepId = context.findProducerByOutput(outputName);
                if (producerStepId == null) {
                    throw new PlanValidationException(stepDefinition.stepId() + " 的语义输入 @" + outputName
                            + " 在因子插槽之前的步骤中找不到产出者（骨架需提供该产出）");
                }
                inputRefs.put(inputName, producerStepId + "." + outputName);
                dataDependencies.add(producerStepId);
            } else {
                int dot = ref.indexOf('.');
                String stepRef = ref.substring(0, dot);
                inputRefs.put(inputName, resolveStepRef(stepRef, runtimeIdByDefinitionId, context) + ref.substring(dot));
            }
        });
        return new ResolvedInputs(inputRefs, dataDependencies);
    }

    /** 步骤引用解析：本组映射（模块内）优先，其次全局注册表（骨架固定步骤），都不命中保持原样 */
    private static String resolveStepRef(String stepRef, Map<String, String> runtimeIdByDefinitionId,
                                         PlanWeavingContext context) {
        String mapped = runtimeIdByDefinitionId.get(stepRef);
        if (mapped != null) {
            return mapped;
        }
        String global = context.runtimeIdOf(stepRef);
        return global != null ? global : stepRef;
    }
}
