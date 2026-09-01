package com.merchant.analysis.execution;

import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.plan.Plan;
import com.merchant.analysis.plan.PlanStep;
import com.merchant.analysis.playbook.FactorModule;
import com.merchant.analysis.playbook.PlaybookLoader;
import com.merchant.analysis.time.TimeResolver;
import com.merchant.analysis.understanding.QueryUnderstanding;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 执行服务：按拓扑分层并发执行 —— 同层步骤（依赖已全部满足）提交线程池一起跑，
 * 层间屏障等待全部完成后再调度下一层。结果写入 {@link AnalysisContext}（仅在主线程、
 * 层间写，无需并发容器），每步状态通过 {@link RunRepository} 落库。
 *
 * 支持因子模块声明证伪规则（playbook 的 falsification 节点）：用户假设因子
 * （candidate_factor）的步骤产出命中规则（某产出为 null）时标记 hypothesis_broken，
 * 跳过该因子后续步骤，并在结论输入中注明假设不成立。默认因子命中规则不标记——
 * 被证伪的只能是用户的假设。
 */
@Service
public class ExecutionService {

    private final CapabilityCatalog catalog;
    private final Map<String, CapabilityExecutor> executors;  // Spring 按 bean 名注入
    private final TimeResolver timeResolver;
    private final RunRepository runRepository;
    private final PlaybookLoader playbookLoader;
    /** 层内并发执行线程池；大小与数据源连接池（Hikari maximum-pool-size）匹配 */
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public ExecutionService(CapabilityCatalog catalog, Map<String, CapabilityExecutor> executors,
                            TimeResolver timeResolver, RunRepository runRepository,
                            PlaybookLoader playbookLoader) {
        this.catalog = catalog;
        this.executors = executors;
        this.timeResolver = timeResolver;
        this.runRepository = runRepository;
        this.playbookLoader = playbookLoader;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    public AnalysisContext execute(Plan plan, QueryUnderstanding understanding, String runId) {
        return executeInternal(plan, understanding, runId, Map.of());
    }

    /**
     * 断点续跑：从 step_run 恢复已成功步骤的产出，只重跑未完成步骤。
     * 对用户假设因子的已完成步骤重放证伪规则，恢复 hypothesis_broken 标记，
     * 保证该因子后续量化步骤仍被跳过。
     */
    public AnalysisContext resume(String runId, Plan plan, QueryUnderstanding understanding) {
        Map<String, Map<String, Object>> completed = runRepository.loadSuccessfulStepOutputs(runId);
        return executeInternal(plan, understanding, runId, completed);
    }

    private AnalysisContext executeInternal(Plan plan, QueryUnderstanding understanding, String runId,
                                            Map<String, Map<String, Object>> completedSteps) {
        AnalysisContext ctx = createContext(understanding, completedSteps);
        restoreFalsificationState(plan, completedSteps, ctx);

        for (List<PlanStep> level : topoSortLevels(plan)) {
            executeLevel(level, runId, ctx, completedSteps);
        }
        // TODO: 追问交互（假设证伪后向用户追问澄清、或缺参数时反问）留待后续迭代
        return ctx;
    }

    /** 运行准备：初始化 AnalysisContext，并恢复断点续跑中已成功步骤的产出 */
    private AnalysisContext createContext(QueryUnderstanding understanding,
                                          Map<String, Map<String, Object>> completedSteps) {
        AnalysisContext ctx = new AnalysisContext(
                understanding,
                timeResolver.resolve(understanding.time_range()),
                timeResolver.resolve(understanding.comparison_baseline()));
        completedSteps.forEach(ctx::putStepResult);
        return ctx;
    }

    /** 断点恢复：对已完成步骤重放证伪规则，恢复 hypothesis_broken 标记（首次执行为空遍历） */
    private void restoreFalsificationState(Plan plan, Map<String, Map<String, Object>> completedSteps,
                                           AnalysisContext ctx) {
        for (PlanStep step : plan.steps()) {
            Map<String, Object> outputs = completedSteps.get(step.stepId());
            if (outputs != null) {
                checkFalsification(step, outputs, ctx);
            }
        }
    }

    /** 单层执行：筛选本层待跑步骤 → 并发提交 → 主线程回收结果并做证伪判定（层间屏障） */
    private void executeLevel(List<PlanStep> level, String runId, AnalysisContext ctx,
                              Map<String, Map<String, Object>> completedSteps) {
        List<PlanStep> toRun = selectRunnableSteps(level, runId, ctx, completedSteps);
        List<Future<Map<String, Object>>> futures = submitSteps(toRun, runId, ctx);
        collectStepResults(toRun, futures, ctx);
    }

    /** 步骤过滤：已成功（断点续跑）/ 被证伪因子后续 / 无能力收尾步 分别跳过、记 SKIPPED、记 SUCCESS */
    private List<PlanStep> selectRunnableSteps(List<PlanStep> level, String runId, AnalysisContext ctx,
                                               Map<String, Map<String, Object>> completedSteps) {
        List<PlanStep> toRun = new ArrayList<>();
        for (PlanStep step : level) {
            if (completedSteps.containsKey(step.stepId())) {
                continue;   // 断点续跑：已成功步骤直接跳过，产出已从 step_run 恢复到 ctx
            }
            if (shouldSkipByFalsification(step, ctx)) {
                markSkippedByFalsification(runId, step);
                continue;
            }
            if (isNoopStep(step)) {
                // 收尾步无能力，仅作依赖汇聚；结论由 ConclusionService 生成
                runRepository.markStepSuccess(runId, step.stepId(), Map.of());
                continue;
            }
            toRun.add(step);
        }
        return toRun;
    }

    /** 动态调整：假设已被证伪 → 跳过被证伪因子的后续步骤。
     *  证伪标记只在层间由主线程置位，因此同层内不会出现「先跑了后被跳」的竞态 */
    private boolean shouldSkipByFalsification(PlanStep step, AnalysisContext ctx) {
        return ctx.hypothesisBroken() && step.factor() != null && step.factor().equals(ctx.falsifiedFactor());
    }

    private void markSkippedByFalsification(String runId, PlanStep step) {
        runRepository.markStepSkipped(runId, step.stepId(),
                "假设证伪，跳过 " + step.factor() + " 因子后续步骤");
    }

    private boolean isNoopStep(PlanStep step) {
        return step.capabilities().isEmpty();
    }

    /** 层内并发提交：同层步骤互不依赖，一起提交线程池 */
    private List<Future<Map<String, Object>>> submitSteps(List<PlanStep> steps, String runId,
                                                          AnalysisContext ctx) {
        return steps.stream()
                .map(step -> pool.submit(() -> runStep(step, runId, ctx)))
                .toList();
    }

    /** 结果回收：等待本层全部完成（层间屏障），写 ctx 并做证伪判定 */
    private void collectStepResults(List<PlanStep> steps, List<Future<Map<String, Object>>> futures,
                                    AnalysisContext ctx) {
        for (int i = 0; i < steps.size(); i++) {
            PlanStep step = steps.get(i);
            Map<String, Object> stepOutputs = await(futures.get(i), step.stepId());
            ctx.putStepResult(step.stepId(), stepOutputs);

            // 动态调整：用户假设因子的步骤产出命中证伪规则 → 用户假设不成立
            checkFalsification(step, stepOutputs, ctx);
        }
    }

    /**
     * 证伪判定：仅作用于用户假设因子（candidate_factor）且该因子在 playbook 声明了
     * falsification 规则——规则产出字段为 null 即标记假设证伪。默认因子命中规则不标记。
     */
    private void checkFalsification(PlanStep step, Map<String, Object> stepOutputs, AnalysisContext ctx) {
        if (ctx.hypothesisBroken()) {
            return;
        }
        String factor = step.factor();
        if (factor == null || !factor.equals(ctx.understanding().candidate_factor())) {
            return;
        }
        FactorModule.Falsification rule = playbookLoader.factor(factor).falsification();
        if (rule != null && stepOutputs.containsKey(rule.output()) && stepOutputs.get(rule.output()) == null) {
            ctx.markHypothesisBroken(factor, rule.note());
        }
    }

    /**
     * 单步执行（工作线程）：只做能力调用与状态落库，不写 ctx ——
     * ctx 的写入统一由主线程在层间完成，保证层内 ctx 只读、无并发修改。
     */
    private Map<String, Object> runStep(PlanStep step, String runId, AnalysisContext ctx) {
        // params = 步骤参数 + inputs 引用解析值
        Map<String, Object> params = new LinkedHashMap<>(step.params());
        step.inputs().forEach((k, ref) -> {
            int dot = ref.indexOf('.');
            params.put(k, ctx.lookup(ref.substring(0, dot), ref.substring(dot + 1)));
        });

        runRepository.markStepRunning(runId, step.stepId());
        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        try {
            for (String capability : step.capabilities()) {
                CapabilityExecutor executor = executors.get(catalog.executorBean(capability));
                if (executor == null) {
                    throw new IllegalStateException("找不到能力执行器 bean: " + catalog.executorBean(capability));
                }
                stepOutputs.putAll(executor.execute(params, ctx));
            }
        } catch (Exception e) {
            runRepository.markStepFailed(runId, step.stepId(), String.valueOf(e.getMessage()));
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
        runRepository.markStepSuccess(runId, step.stepId(), stepOutputs);
        return stepOutputs;
    }

    private static Map<String, Object> await(Future<Map<String, Object>> future, String stepId) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待步骤 " + stepId + " 执行时被中断", e);
        } catch (ExecutionException e) {
            // 工作线程内已将该步标记 FAILED，原样抛出结束本次 run
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
        }
    }

    /** Kahn 拓扑分层：每轮 indegree=0 的步骤为一层，同层互不依赖可并发（plan 已通过校验无环） */
    private List<List<PlanStep>> topoSortLevels(Plan plan) {
        Map<String, Integer> indegree = new HashMap<>();
        plan.steps().forEach(s -> indegree.put(s.stepId(), s.dependsOn().size()));
        List<List<PlanStep>> levels = new ArrayList<>();
        List<PlanStep> remaining = new ArrayList<>(plan.steps());
        while (!remaining.isEmpty()) {
            List<PlanStep> ready = remaining.stream().filter(s -> indegree.get(s.stepId()) == 0).toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("plan 依赖图存在环（应已在 PlanValidator 拦截）");
            }
            levels.add(ready);
            for (PlanStep s : ready) {
                remaining.remove(s);
                remaining.forEach(r -> {
                    if (r.dependsOn().contains(s.stepId())) {
                        indegree.merge(r.stepId(), -1, Integer::sum);
                    }
                });
            }
        }
        return levels;
    }
}
