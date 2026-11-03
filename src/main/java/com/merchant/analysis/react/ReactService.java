package com.merchant.analysis.react;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.llm.LlmClient;
import com.merchant.analysis.persistence.RunRepository;
import com.merchant.analysis.scene.ReactSceneCatalog;
import com.merchant.analysis.tool.*;
import com.merchant.analysis.understanding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

@Service
public class ReactService {
    private final ToolRuntime tools;
    private final ReactSceneCatalog scenes;
    private final LlmClient llm;
    private final ReactSettings settings;
    private final BoundedCalls bounded;
    private final ReactRepository repository;
    private final RunRepository runs;
    private final ReactCompletion completion;
    private final ReactAnswers answers;
    private final String decisionTemplate;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ReactService(ToolRuntime tools, ReactSceneCatalog scenes, LlmClient llm, ReactSettings settings,
                        BoundedCalls bounded, ReactRepository repository, RunRepository runs,
                        ReactCompletion completion, ReactAnswers answers,
                        @Value("classpath:prompts/react_decision.txt") Resource decision) {
        this.tools=tools; this.scenes=scenes; this.llm=llm; this.settings=settings; this.bounded=bounded;
        this.repository=repository; this.runs=runs; this.completion=completion; this.answers=answers;
        try { decisionTemplate=decision.getContentAsString(StandardCharsets.UTF_8); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public ReactResult execute(String runId, QueryUnderstanding understanding, List<ChainContext> chains) {
        if (understanding.task() == null || understanding.resolved_time() == null)
            throw new IllegalArgumentException("ReAct 需要完整任务和已固定的时间");
        if (!active.add(runId)) return busy(runId);
        try {
            ReactState state = repository.load(runId);
            var time = understanding.resolved_time();
            AnalysisContext ctx = new AnalysisContext(understanding, time.current(), time.baseline());
            runs.loadSuccessfulStepOutputs(runId).forEach(ctx::putStepResult);
            if (state == null) {
                var existing = runs.findRun(runId);
                if (existing != null && !"RUNNING".equals(existing.status())) {
                    ReactStatus terminal = "SUCCESS".equals(existing.status()) ? ReactStatus.COMPLETED : ReactStatus.valueOf(existing.status());
                    return new ReactResult(terminal, existing.conclusion(), ctx.stepResults(), List.of(), Map.of("restored_terminal_run", true));
                }
                state = new ReactState(); state.limits = settings.snapshot();
                state.startedAt = System.currentTimeMillis(); state.deadline = state.startedAt + state.limits.getTotalTimeoutMs();
                state.scene = understanding.routing() == null ? null : understanding.routing().matched_react_scene();
                repository.save(runId, state);
            }
            if (!state.finalized && state.history.size() < understanding.task().fact_references().size()) {
                try { seed(runId, state, ctx, chains); }
                catch (RuntimeException e) {
                    stop(state, ReactStatus.FAILED, "无法恢复所引用的历史分析结果");
                    event(runId, state, "HISTORY", "binding", understanding.task().fact_references(), null, "FAILED", System.currentTimeMillis(), message(e));
                }
            }
            if (state.finalized) {
                var stored = runs.findRun(runId);
                String expected = state.status == ReactStatus.COMPLETED ? "SUCCESS" : state.status.name();
                if (stored != null && !expected.equals(stored.status()))
                    runs.finishRun(runId, expected, false, state.answer, state.status == ReactStatus.COMPLETED ? null : state.reason);
                return result(state, ctx);
            }
            ToolSession session = tools.newSession(ctx);
            try {
                session.loadTools(state.loaded);
                if (state.status == ReactStatus.RUNNING) loop(runId, state, ctx, session);
            } catch (RuntimeException e) {
                stop(state, ReactStatus.FAILED, "分析执行异常，已保留成功查询的结果");
                event(runId, state, "RUNTIME", "execution", null, null, "FAILED", System.currentTimeMillis(), message(e));
            }
            finish(runId, state, ctx);
            return result(state, ctx);
        } finally { active.remove(runId); }
    }
    public ReactResult resume(String runId, QueryUnderstanding understanding) { return execute(runId, understanding, null); }

    private void loop(String runId, ReactState state, AnalysisContext ctx, ToolSession session) {
        while (state.status == ReactStatus.RUNNING) {
            if (state.modelCalls >= state.limits.getMaxModelCalls() - 1) { stop(state, ReactStatus.BUDGET_EXCEEDED, "模型调用次数已达到上限，已预留收尾预算"); break; }
            long available = remaining(state) - state.limits.getAnswerReserveMs();
            if (available <= 0) { stop(state, ReactStatus.BUDGET_EXCEEDED, "已达到执行时限，保留收尾时间"); break; }
            String prompt = decisionPrompt(state, ctx, session);
            if (prompt.length() > state.limits.getMaxPromptChars()) { stop(state, ReactStatus.BUDGET_EXCEEDED, "模型上下文超过长度预算"); break; }
            long timeout = Math.min(state.limits.getModelTimeoutMs(), remaining(state) - state.limits.getAnswerReserveMs());
            if (timeout <= 0) { stop(state, ReactStatus.BUDGET_EXCEEDED, "已达到执行时限"); break; }
            String response;
            state.modelCalls++; repository.save(runId, state);
            long started = System.currentTimeMillis();
            try {
                response = bounded.model(() -> llm.chat(prompt, Duration.ofMillis(timeout)), timeout);
                event(runId, state, "MODEL", "decision", prompt, response, "RECEIVED", started, null);
            } catch (Exception e) {
                event(runId, state, "MODEL", "decision", prompt, null, "FAILED", started, message(e));
                stop(state, e instanceof TimeoutException ? ReactStatus.BUDGET_EXCEEDED : ReactStatus.FAILED, "模型调用失败或超过单次时限");
                break;
            }
            try {
                ReactAction action = ReactAction.parse(response);
                switch (action.action()) {
                    case "load_tools" -> load(runId, state, session, action);
                    case "call_tool" -> call(runId, state, ctx, session, action);
                    case "inspect_result" -> inspect(runId, state, ctx, action);
                    case "finish" -> {
                        state.coverage = completion.check(action, state, ctx);
                        boolean all = state.coverage.stream().allMatch(c -> c.status().equals("completed"));
                        boolean some = state.coverage.stream().anyMatch(c -> !c.status().equals("missing"));
                        ReactStatus status = action.status() == ReactStatus.COMPLETED && all ? ReactStatus.COMPLETED
                                : action.status() == ReactStatus.CAPABILITY_MISSING && !some && !completion.anyCapability(ctx)
                                ? ReactStatus.CAPABILITY_MISSING : ReactStatus.PARTIAL;
                        stop(state, status, all ? action.reason() : "仍有子需求缺少完整证据或所需能力");
                        event(runId, state, "FINISH", "coverage", action.requirements(), state.coverage, status.name(), System.currentTimeMillis(), state.reason);
                    }
                }
            } catch (Exception e) {
                failure(runId, state, "action", response, e);
            }
            repository.save(runId, state);
        }
        repository.save(runId, state);
    }
    private void load(String runId, ReactState state, ToolSession session, ReactAction action) {
        Set<String> requested = new LinkedHashSet<>(action.names());
        if (requested.isEmpty() || requested.size() > state.limits.getMaxLoadBatch()) throw new IllegalArgumentException("工具加载批次超出预算");
        Set<String> merged = new LinkedHashSet<>(state.loaded); merged.addAll(requested);
        if (merged.size() > state.limits.getMaxLoadedTools()) throw new IllegalArgumentException("已加载工具数量超出预算");
        int before = session.loadedNames().size();
        var definitions = session.loadTools(requested);
        state.loaded = new ArrayList<>(session.loadedNames());
        progress(state, state.loaded.size() > before);
        observe(state, "load_tools", "SUCCESS", null, Map.of("loaded", action.names()), null);
        event(runId, state, "LOAD_TOOLS", "load_tools", action.names(), definitions, "SUCCESS", System.currentTimeMillis(), null);
    }
    private void call(String runId, ReactState state, AnalysisContext ctx, ToolSession session, ReactAction action) {
        if (state.toolCalls >= state.limits.getMaxToolCalls()) { stop(state, ReactStatus.BUDGET_EXCEEDED, "工具调用次数已达到上限"); return; }
        String id = "T" + ++state.callSequence;
        state.toolCalls++; repository.save(runId, state); // 错误调用同样消耗预算。
        repository.step(runId, id, action.tool());
        ToolInvocation invocation = null;
        long started = System.currentTimeMillis();
        try {
            invocation = session.prepare(id, action.tool(), action.arguments());
            String fingerprint = ReactViews.fingerprint(Map.of("name", invocation.name(), "arguments", invocation.arguments()));
            ReactState.Call cached = state.calls.stream().filter(c -> c.fingerprint().equals(fingerprint)
                    && ctx.stepResults().containsKey(c.callId())).findFirst().orElse(null);
            ToolExecution execution;
            if (cached != null) {
                execution = new ToolExecution(invocation.name(), invocation.arguments(), invocation.references(), ctx.stepResults().get(cached.callId()));
            } else {
                AnalysisContext snapshot = new AnalysisContext(ctx.understanding(), ctx.current(), ctx.baseline());
                ctx.stepResults().forEach((key, value) -> snapshot.putStepResult(key, ToolExecution.snapshot(value)));
                long timeout = Math.min(state.limits.getToolTimeoutMs(), remaining(state) - state.limits.getAnswerReserveMs());
                ToolInvocation bound = invocation;
                execution = bounded.tool(() -> tools.invoke(bound, snapshot), timeout);
            }
            // 只有主线程在受控调用完成后发布结果；超时后台线程没有此路径。
            session.record(id, execution);
            runs.markStepSuccess(runId, id, execution.output());
            state.calls.add(new ReactState.Call(id, invocation, fingerprint, cached == null ? null : cached.callId()));
            state.origins.put(id, List.of(invocation.name()));
            state.originArguments.put(id, invocation.arguments());
            progress(state, cached == null);
            observe(state, "call_tool", cached == null ? "SUCCESS" : "CACHED", id, ReactViews.preview(execution.output()), null);
            event(runId, state, "TOOL", invocation.name(), invocation, execution.output(), cached == null ? "SUCCESS" : "CACHED", started, null);
        } catch (Exception e) {
            runs.markStepFailed(runId, id, message(e));
            event(runId, state, "TOOL", action.tool(), invocation == null ? action.arguments() : invocation, null, "FAILED", started, message(e));
            observe(state, "call_tool", "FAILED", id, null, message(e));
            if (e instanceof TimeoutException) stop(state, ReactStatus.BUDGET_EXCEEDED, "工具调用超过时限，已停止发布该调用结果");
            else failure(runId, state, action.tool(), action.arguments(), e);
        }
    }
    private void inspect(String runId, ReactState state, AnalysisContext ctx, ReactAction action) {
        Object value = ReactViews.reference(action.reference(), ctx);
        Object result;
        String key = action.reference() + ":" + action.offset() + ":" + action.limit();
        if (value instanceof List<?> rows) {
            if (action.offset() > rows.size()) throw new IllegalArgumentException("查看结果的 offset 越界");
            int end = Math.min(rows.size(), action.offset() + action.limit());
            result = Map.of("reference", action.reference(), "offset", action.offset(), "count", rows.size(),
                    "items", rows.subList(action.offset(), end).stream().map(ReactViews::preview).toList(), "truncated", end < rows.size());
        } else {
            if (action.offset() != 0) throw new IllegalArgumentException("非集合结果不接受 offset");
            Map<String, Object> fields = new LinkedHashMap<>(); fields.put("reference", action.reference()); fields.put("value", ReactViews.preview(value)); result = fields;
        }
        progress(state, state.inspected.add(key));
        observe(state, "inspect_result", "SUCCESS", null, result, null);
        event(runId, state, "INSPECT", action.reference(), Map.of("offset", action.offset(), "limit", action.limit()), result, "SUCCESS", System.currentTimeMillis(), null);
    }
    private void seed(String runId, ReactState state, AnalysisContext ctx, List<ChainContext> chains) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (var existing : state.history) aliases.put(existing.runId() + "/" + existing.sourcePath().split("\\.")[0], existing.aliasPath().split("\\.")[0]);
        for (var ref : ctx.understanding().task().fact_references()) {
            if (state.history.stream().anyMatch(h -> h.runId().equals(ref.run_id()) && h.sourcePath().equals(ref.path()))) continue;
            if (chains != null) HistoryFacts.validate(ref, chains);
            Map<String, Map<String, Object>> outputs = chains == null ? runs.loadSuccessfulStepOutputs(ref.run_id())
                    : chains.stream().filter(c -> c.runId().equals(ref.run_id())).findFirst().orElseThrow().stepResults();
            String[] parts = ref.path().split("\\.", 2);
            String origin = ref.run_id() + "/" + parts[0];
            String alias = aliases.get(origin);
            if (alias == null) { alias = "H" + (aliases.size() + 1); aliases.put(origin, alias); }
            if (!ctx.stepResults().containsKey(alias)) {
                Map<String, Object> values = outputs.get(parts[0]);
                if (values == null) throw new IllegalArgumentException("历史引用结果已不存在");
                ctx.putStepResult(alias, ToolExecution.snapshot(values));
                repository.step(runId, alias, "历史结果引用"); runs.markStepSuccess(runId, alias, values);
            }
            String aliasPath = alias + "." + parts[1];
            ReactViews.reference(aliasPath, ctx);
            RunRepository.RunRecord source = runs.findRun(ref.run_id());
            if (source != null) {
                QueryUnderstanding sourceUnderstanding = RunRepository.understandingFromJson(source.understandingJson());
                ReactState sourceState = repository.load(ref.run_id());
                if (sourceState != null) {
                    var sourceTime = sourceUnderstanding.resolved_time();
                    AnalysisContext sourceContext = new AnalysisContext(sourceUnderstanding,
                            sourceTime == null ? ctx.current() : sourceTime.current(), sourceTime == null ? ctx.baseline() : sourceTime.baseline());
                    outputs.forEach(sourceContext::putStepResult);
                    state.lineage.put(aliasPath, completion.lineage(ref.path(), sourceState, sourceContext));
                    state.origins.put(alias, sourceState.origins.getOrDefault(parts[0], List.of()));
                    state.originArguments.put(alias, sourceState.originArguments.getOrDefault(parts[0], Map.of()));
                } else {
                    var plan = RunRepository.planFromJson(source.planJson());
                    var step = plan.steps().stream().filter(s -> s.stepId().equals(parts[0])).findFirst().orElse(null);
                    state.origins.put(alias, step == null ? List.of() : step.capabilities());
                    if (sourceUnderstanding.resolved_time() != null) state.originArguments.put(alias, Map.of(
                            "time_range", sourceUnderstanding.resolved_time().current(), "baseline", sourceUnderstanding.resolved_time().baseline()));
                }
            }
            state.history.add(new ReactState.HistoryBinding(ref.run_id(), ref.path(), aliasPath));
            repository.save(runId, state);
        }
    }
    private String decisionPrompt(ReactState state, AnalysisContext ctx, ToolSession session) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", ctx.understanding().task()); input.put("time", ctx.understanding().resolved_time());
        input.put("scene_id", state.scene);
        input.put("scene_guidance", state.scene == null ? null : scenes.get(state.scene).guidance());
        input.put("catalog", session.summaries()); input.put("loaded_tools", session.loadedDefinitions());
        input.put("results", ReactViews.preview(ctx.stepResults())); input.put("calls", state.calls);
        input.put("history_bindings", state.history); input.put("observations", state.observations);
        input.put("budget", Map.of("model_calls_remaining", state.limits.getMaxModelCalls() - state.modelCalls - 1,
                "tool_calls_remaining", state.limits.getMaxToolCalls() - state.toolCalls,
                "max_load_batch", state.limits.getMaxLoadBatch(), "remaining_ms", Math.max(0, remaining(state))));
        return decisionTemplate.replace("{{input}}", ReactViews.json(input));
    }
    private void finish(String runId, ReactState state, AnalysisContext ctx) {
        if (state.status == ReactStatus.RUNNING) stop(state, ReactStatus.PARTIAL, "执行尚未完成");
        if (state.coverage.isEmpty()) {
            List<ReactState.Coverage> coverage = new ArrayList<>();
            for (int i=0; i<ctx.understanding().task().requirements().size(); i++)
                coverage.add(new ReactState.Coverage(i, "missing", List.of(), "执行已停止，尚未验证该需求完成"));
            state.coverage = coverage;
        }
        if (!state.answerAttempted && state.modelCalls < state.limits.getMaxModelCalls() && remaining(state) > 0) {
            String prompt = answers.prompt(state, ctx);
            long timeout = Math.min(state.limits.getModelTimeoutMs(), remaining(state));
            if (prompt.length() <= state.limits.getMaxPromptChars()) {
                state.modelCalls++; state.answerAttempted = true; repository.save(runId, state);
                long started = System.currentTimeMillis();
                try {
                    String answer = bounded.model(() -> llm.chat(prompt, Duration.ofMillis(timeout)), timeout);
                    if (!answers.grounded(answer, ctx) || state.status != ReactStatus.COMPLETED && answers.claimsComplete(answer)) throw new IllegalArgumentException("回答未通过数字或实体出处校验");
                    state.answer = state.status == ReactStatus.COMPLETED || answer.startsWith("本次分析未全部完成")
                            ? answer : "本次分析未全部完成：" + state.reason + "。\n" + answer;
                    event(runId, state, "ANSWER", "answer", prompt, answer, "SUCCESS", started, null);
                } catch (Exception e) {
                    event(runId, state, "ANSWER", "answer", prompt, null, "FAILED", started, message(e));
                    if (state.status == ReactStatus.COMPLETED) stop(state, ReactStatus.PARTIAL, "数据已查询，但完整回答生成或校验失败");
                }
            }
        }
        if (state.answer == null) {
            if (state.status == ReactStatus.COMPLETED) stop(state, ReactStatus.PARTIAL, "完整回答未能在预算内生成");
            state.answer = answers.fallback(state, ctx);
        }
        state.finalized = true;
        repository.save(runId, state);
        runs.finishRun(runId, state.status == ReactStatus.COMPLETED ? "SUCCESS" : state.status.name(), false, state.answer,
                state.status == ReactStatus.COMPLETED ? null : state.reason);
    }
    private void failure(String runId, ReactState state, String name, Object input, Exception error) {
        int failures = state.errors.merge(ReactViews.fingerprint(Map.of("name", name, "input", input == null ? "" : input, "error", message(error))), 1, Integer::sum);
        progress(state, false);
        observe(state, "error", "FAILED", null, null, message(error));
        event(runId, state, "ACTION_ERROR", name, input, null, "FAILED", System.currentTimeMillis(), message(error));
        if (failures >= state.limits.getMaxSameErrors()) stop(state, ReactStatus.LOOP_DETECTED, "同类调用错误重复出现，已停止重试");
    }
    private static void progress(ReactState state, boolean changed) {
        state.noProgress = changed ? 0 : state.noProgress + 1;
        if (state.noProgress >= state.limits.getMaxNoProgress()) stop(state, ReactStatus.LOOP_DETECTED, "连续多轮没有新增有效结果，已停止重复执行");
    }
    private static void observe(ReactState state, String action, String status, String id, Object data, String error) {
        state.observations.add(new ReactState.Observation(action, status, id, data, error));
    }
    private void event(String runId, ReactState state, String kind, String name, Object input, Object output, String status, long started, String error) {
        state.eventSequence = Math.max(state.eventSequence, repository.lastSequence(runId)) + 1;
        repository.event(runId, state.eventSequence, kind, name, input, output, status, Math.max(0, System.currentTimeMillis() - started), error);
    }
    private static void stop(ReactState state, ReactStatus status, String reason) { state.status = status; state.reason = reason; }
    private static long remaining(ReactState state) { return state.deadline - System.currentTimeMillis(); }
    private static String message(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
    private ReactResult busy(String runId) {
        ReactState state = repository.load(runId);
        return new ReactResult(ReactStatus.RUNNING, "分析正在执行，请稍后查看结果。", runs.loadSuccessfulStepOutputs(runId),
                state == null ? List.of() : state.coverage, Map.of("running", true));
    }
    private static ReactResult result(ReactState state, AnalysisContext ctx) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("model_calls", state.modelCalls); stats.put("tool_calls", state.toolCalls);
        stats.put("loaded_tools", state.loaded); stats.put("reason", state.reason);
        stats.put("started_at", state.startedAt); stats.put("deadline", state.deadline);
        return new ReactResult(state.status, state.answer, Collections.unmodifiableMap(new LinkedHashMap<>(ctx.stepResults())), state.coverage, stats);
    }
}
