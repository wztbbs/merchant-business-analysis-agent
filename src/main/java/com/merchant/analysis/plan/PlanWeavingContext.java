package com.merchant.analysis.plan;

import com.merchant.analysis.playbook.Skeleton;
import com.merchant.analysis.playbook.StepTemplate;
import com.merchant.analysis.understanding.QueryUnderstanding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan 编织上下文：持有编织过程中的全部可变状态——已编织的步骤实例、运行时步骤 id 计数、
 * 产出字段 → 生产者步骤索引、模板变量。
 * 让 {@link PlanWeaver} 的主流程只表达"织什么"，"织到哪一步了"的状态管理收敛在这里。
 */
class PlanWeavingContext {

    private final Skeleton skeleton;
    private final TemplateContext templateContext;
    private final List<PlanStep> alreadyWovenSteps = new ArrayList<>();
    /** 产出字段 → 生产者步骤 id（同一骨架内产出字段唯一，由 PlaybookLoader 启动时校验保证） */
    private final Map<String, String> producerStepIdByOutputName = new HashMap<>();
    /** 全局的 定义id → 运行时id 注册表：因子模块引用骨架固定步骤的定义 id 时靠它解析 */
    private final Map<String, String> runtimeIdByDefinitionId = new HashMap<>();
    private int nextStepNo = 1;

    PlanWeavingContext(Skeleton skeleton, QueryUnderstanding understanding) {
        this.skeleton = skeleton;
        this.templateContext = TemplateContext.from(understanding);
    }

    Skeleton skeleton() {
        return skeleton;
    }

    TemplateContext templateContext() {
        return templateContext;
    }

    String allocateStepId() {
        return "S" + nextStepNo++;
    }

    /** 为一组步骤定义统一分配运行时 id（同时注册进全局映射），返回 定义id → 运行时id 的本组映射 */
    Map<String, String> allocateRuntimeIds(List<StepTemplate> stepDefinitions) {
        Map<String, String> groupRuntimeIds = new HashMap<>();
        for (StepTemplate stepDefinition : stepDefinitions) {
            String runtimeId = allocateStepId();
            groupRuntimeIds.put(stepDefinition.stepId(), runtimeId);
            runtimeIdByDefinitionId.put(stepDefinition.stepId(), runtimeId);
        }
        return groupRuntimeIds;
    }

    /** 全局解析定义 id → 运行时 id；未注册返回 null（如跨模块引用骨架步骤时由调用方兜底） */
    String runtimeIdOf(String stepDefinitionId) {
        return runtimeIdByDefinitionId.get(stepDefinitionId);
    }

    /** 步骤实例入列，并把它的产出字段注册进生产者索引（语义引用 @产出key 靠它反查） */
    void addStep(PlanStep planStep) {
        alreadyWovenSteps.add(planStep);
        for (String outputName : planStep.outputs()) {
            // 因子步骤可能与固定步骤撞产出字段名：先到先得（先编织的是骨架固定步骤）
            producerStepIdByOutputName.putIfAbsent(outputName, planStep.stepId());
        }
    }

    List<PlanStep> alreadyWovenSteps() {
        return alreadyWovenSteps;
    }

    /** 按产出字段反查生产者步骤 id；找不到返回 null，由调用方决定报错措辞 */
    String findProducerByOutput(String outputName) {
        return producerStepIdByOutputName.get(outputName);
    }

    Plan toPlan() {
        return new Plan(skeleton.scene(), List.copyOf(alreadyWovenSteps));
    }
}
