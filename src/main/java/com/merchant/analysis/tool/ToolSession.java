package com.merchant.analysis.tool;

import com.merchant.analysis.execution.AnalysisContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单次探索会话只允许调用已加载定义的工具；不包含模型循环。 */
public final class ToolSession {
    public record Call(String callId, ToolExecution execution) {}
    private final ToolRuntime runtime;
    private final ToolCatalog catalog;
    private final AnalysisContext context;
    private final Set<String> loaded = new LinkedHashSet<>();
    private final List<Call> history = new ArrayList<>();

    ToolSession(ToolRuntime runtime, ToolCatalog catalog, AnalysisContext context) {
        if (context == null) throw new IllegalArgumentException("工具会话需要分析上下文");
        this.runtime = runtime;
        this.catalog = catalog;
        this.context = context;
    }
    public List<ToolSummary> summaries() { return catalog.summaries(); }
    public Set<String> loadedNames() { return Set.copyOf(loaded); }
    public List<ToolDefinition> loadTools(Collection<String> names) {
        List<ToolDefinition> definitions = catalog.loadDefinitions(names);
        definitions.forEach(definition -> loaded.add(definition.name()));
        return definitions;
    }
    public List<ToolDefinition> loadedDefinitions() { return catalog.loadDefinitions(loaded); }
    public List<Call> history() { return List.copyOf(history); }
    public Map<String, Object> execute(String callId, String name, Map<String, Object> arguments) {
        ToolExecution execution = runtime.invoke(prepare(callId, name, arguments), context);
        return record(callId, execution);
    }
    public ToolInvocation prepare(String callId, String name, Map<String, Object> arguments) {
        checkCall(callId, name);
        return runtime.prepare(name, arguments, context);
    }
    public Map<String, Object> record(String callId, ToolExecution execution) {
        checkCall(callId, execution.name());
        context.putStepResult(callId, execution.output());
        history.add(new Call(callId, execution));
        return execution.output();
    }
    private void checkCall(String callId, String name) {
        if (!loaded.contains(name)) throw new IllegalArgumentException("工具尚未加载: " + name);
        if (callId == null || !callId.matches("[A-Za-z][A-Za-z0-9_]*") || context.stepResults().containsKey(callId))
            throw new IllegalArgumentException("调用 ID 非法或已存在: " + callId);
    }
}
