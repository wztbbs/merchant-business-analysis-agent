package com.merchant.analysis.plan;

import com.merchant.analysis.playbook.FactorModule;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.playbook.Skeleton;
import com.merchant.analysis.playbook.StepTemplate;
import com.merchant.analysis.understanding.QueryUnderstanding;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan 编织器 —— 纯确定性代码，不调 LLM：
 * 按场景选骨架 → FactorSelector 选定因子清单 → 分阶段编织：
 * 骨架步骤 → 因子步骤（逐模块展开）→ 收尾步骤。
 *
 * 模板里的步骤 id 是语义化的定义 id（如 confirm_change_fact），编织时由
 * {@link PlanWeavingContext} 统一分配运行时 id（S1、S2…）；因子对骨架产出的引用写
 * 语义形式 @产出key（如 @metric_delta），编织时反查生产者步骤重写为具体引用
 * （S1.metric_delta）并自动补 depends_on——因子模块不耦合骨架的步骤编号。
 */
@Service
public class PlanWeaver {

    private final PlaybookLoader playbookLoader;

    public PlanWeaver(PlaybookLoader playbookLoader) {
        this.playbookLoader = playbookLoader;
    }

    public Plan weave(QueryUnderstanding understanding) {
        Skeleton skeleton = playbookLoader.skeleton(understanding.primaryScene());
        PlanWeavingContext context = new PlanWeavingContext(skeleton, understanding);

        weaveSkeletonSteps(context);
        weaveFactorSteps(context, understanding);
        weaveFinalStep(context);

        Plan plan = context.toPlan();
        PlanPrinter.print(plan, understanding);
        return plan;
    }

    /**
     * 编织骨架步骤（plan 中属于骨架的部分：确认事实、贡献拆解等固定步骤）。
     * 模板里这些步骤之间的依赖写的是定义 id（如"贡献度拆解"depends_on: [confirm_change_fact]），
     * 这里统一分配运行时 id（S1、S2），并把这些依赖引用一并改写成运行时 id。
     */
    private void weaveSkeletonSteps(PlanWeavingContext context) {
        weaveSteps(context.skeleton().fixedSteps(), null, context);
    }

    /**
     * 编织因子步骤（plan 中属于因子模块的部分）：FactorSelector 按插槽配置的策略
     * （假设因子必选、默认因子去重追加）选定因子清单，逐个因子模块展开到 plan 中，
     * 运行时 id 接在骨架步骤之后分配。
     */
    private void weaveFactorSteps(PlanWeavingContext context, QueryUnderstanding understanding) {
        Skeleton.FactorSlot slot = context.skeleton().factorSlot();
        if (slot == null) {
            return;
        }
        for (String factorName : FactorSelector.selectFactors(slot, understanding)) {
            FactorModule module = playbookLoader.factor(factorName);
            weaveSteps(module.steps(), factorName, context);
        }
    }

    /**
     * 编织收尾步骤：模板声明 depends_on: {scope: previous_steps}，这里展开为全部已编织步骤。
     * 收尾步骤无能力、无产出，仅作 DAG 的依赖汇聚点（结论由 ConclusionService 在全部步骤完成后生成）。
     */
    private void weaveFinalStep(PlanWeavingContext context) {
        StepTemplate finalStepDefinition = context.skeleton().finalStep();
        List<String> allStepIds = context.alreadyWovenSteps().stream().map(PlanStep::stepId).toList();
        context.addStep(instantiatePlanStep(finalStepDefinition, context.allocateStepId(), null,
                allStepIds, Map.of(), context));
    }

    /**
     * 编织一组 plan 步骤（一组骨架步骤，或某个因子模块的全部步骤）：
     * 先为整组统一分配运行时 id，再逐个步骤解析引用、合并依赖、实例化后加入上下文。
     */
    private void weaveSteps(List<StepTemplate> stepDefinitions, String factorName,
                            PlanWeavingContext context) {
        Map<String, String> runtimeIdByDefinitionId = context.allocateRuntimeIds(stepDefinitions);
        for (StepTemplate stepDefinition : stepDefinitions) {
            List<String> controlDependencies =
                    ReferenceResolver.resolveDependsOn(stepDefinition, runtimeIdByDefinitionId, context);
            ReferenceResolver.ResolvedInputs resolvedInputs =
                    ReferenceResolver.resolveInputs(stepDefinition, runtimeIdByDefinitionId, context);
            List<String> dependsOn = DependencyNormalizer.mergeControlAndDataDependencies(
                    controlDependencies, resolvedInputs.dataDependencies());
            context.addStep(instantiatePlanStep(stepDefinition,
                    runtimeIdByDefinitionId.get(stepDefinition.stepId()), factorName,
                    dependsOn, resolvedInputs.inputRefs(), context));
        }
    }

    /** 步骤定义 → 步骤实例：渲染 params 里的 {{xxx}} 占位符，其余字段（capabilities/outputs 等）原样搬运 */
    private PlanStep instantiatePlanStep(StepTemplate stepDefinition, String runtimeStepId, String factorName,
                                         List<String> dependsOn, Map<String, String> inputRefs,
                                         PlanWeavingContext context) {
        Map<String, Object> params = new LinkedHashMap<>();
        stepDefinition.params().forEach(
                (paramName, value) -> params.put(paramName, context.templateContext().render(value)));
        return new PlanStep(runtimeStepId, stepDefinition.name(), factorName, stepDefinition.capabilities(),
                params, inputRefs, dependsOn, stepDefinition.outputs());
    }
}
