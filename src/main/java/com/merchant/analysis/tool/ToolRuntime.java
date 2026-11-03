package com.merchant.analysis.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.calculation.CalculationEngine;
import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityCatalog;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.*;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 所有编排模式共用的引用绑定、时间消解、输入校验、执行和输出校验入口。 */
@Component
public class ToolRuntime {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ToolCatalog tools;
    private final CapabilityCatalog capabilities;
    private final Map<String, CapabilityExecutor> executors;
    private final CalculationEngine calculations;
    private final TimeResolver timeResolver;

    public ToolRuntime(ToolCatalog tools, CapabilityCatalog capabilities, Map<String, CapabilityExecutor> executors,
                       CalculationEngine calculations, TimeResolver timeResolver) {
        this.tools = tools;
        this.capabilities = capabilities;
        this.executors = Map.copyOf(executors);
        this.calculations = calculations;
        this.timeResolver = timeResolver;
    }
    public ToolSession newSession(AnalysisContext context) { return new ToolSession(this, tools, context); }

    public Map<String, Object> execute(String name, Map<String, Object> arguments, AnalysisContext context) {
        return executeDetailed(name, arguments, context).output();
    }
    public ToolExecution executeDetailed(String name, Map<String, Object> arguments, AnalysisContext context) {
        return invoke(prepare(name, arguments, context), context);
    }
    public ToolInvocation prepare(String name, Map<String, Object> arguments, AnalysisContext context) {
        ToolDefinition definition = tools.definition(name);
        var binding = ResultReferences.bind(arguments, context);
        Map<String, Object> params = binding.arguments();
        // 在任何归一化或数据库访问之前拒绝未知字段。
        params.keySet().forEach(key -> {
            if (!definition.inputSchema().path("properties").has(key)) throw new IllegalArgumentException(name + " 未知参数: " + key);
        });
        if (definition.kind().equals("business")) normalizeTime(params, definition, context);
        SchemaValidator.validate(MAPPER.valueToTree(params), definition.inputSchema(), name + ".input");
        return new ToolInvocation(name, params, binding.references());
    }
    public ToolExecution invoke(ToolInvocation invocation, AnalysisContext context) {
        String name = invocation.name();
        ToolDefinition definition = tools.definition(name);
        Map<String, Object> params = invocation.arguments();
        SchemaValidator.validate(MAPPER.valueToTree(params), definition.inputSchema(), name + ".input");
        Map<String, Object> output;
        if (definition.kind().equals("calculation")) output = calculations.execute(name, params);
        else {
            CapabilityExecutor executor = executors.get(capabilities.executorBean(name));
            if (executor == null) throw new IllegalStateException("找不到工具执行器: " + name);
            output = executor.execute(params, context);
        }
        if (output == null) throw new IllegalStateException(name + " 未返回结果对象");
        SchemaValidator.validate(MAPPER.valueToTree(output), definition.outputSchema(), name + ".output");
        return new ToolExecution(name, params, invocation.references(), output);
    }

    /** 一个模板步骤可能调用多个工具；按各工具定义投影共享参数。动态调用使用严格 execute。 */
    public Map<String, Object> selectArguments(String name, Map<String, Object> shared) {
        var properties = tools.definition(name).inputSchema().path("properties");
        Map<String, Object> result = new LinkedHashMap<>();
        shared.forEach((key, value) -> { if (properties.has(key)) result.put(key, value); });
        return result;
    }

    private void normalizeTime(Map<String, Object> params, ToolDefinition definition, AnalysisContext ctx) {
        ResolvedTime frozen = ctx != null && ctx.understanding() != null ? ctx.understanding().resolved_time() : null;
        TimeContext frame = frozen == null ? timeResolver.capture() : frozen.context();
        Object requested = params.get("time_range");
        DateRange current = requested == null && ctx != null ? ctx.current() : range(requested, frame);
        params.put("time_range", current);
        if (definition.inputSchema().path("properties").has("baseline")) {
            Object baseline = params.get("baseline");
            DateRange comparison;
            if (baseline != null) comparison = range(baseline, frame);
            else if (ctx != null && current.equals(ctx.current())) comparison = ctx.baseline();
            else {
                TimeSpec spec = requested instanceof TimeSpec time ? time
                        : requested instanceof Map<?, ?> map && map.containsKey("type")
                        ? MAPPER.convertValue(requested, TimeSpec.class)
                        : TimeSpec.dates(current.start(), LocalDate.parse(current.endExclusive()).minusDays(1).toString());
                comparison = timeResolver.resolvePeriods(spec, null, frame).baseline();
            }
            checkCutoff(comparison, frame);
            params.put("baseline", comparison);
        }
        checkCutoff(current, frame);
    }
    private DateRange range(Object value, TimeContext frame) {
        if (value == null) throw new IllegalArgumentException("独立工具调用需要明确 time_range");
        if (value instanceof DateRange dates) return dates;
        if (value instanceof Map<?, ?> map && map.containsKey("endExclusive")) return MAPPER.convertValue(value, DateRange.class);
        TimeSpec spec = value instanceof TimeSpec time ? time : MAPPER.convertValue(value, TimeSpec.class);
        return timeResolver.resolve(spec, frame);
    }
    private static void checkCutoff(DateRange range, TimeContext frame) {
        if (range.endExclusive().compareTo(LocalDate.parse(frame.data_through()).plusDays(1).toString()) > 0)
            throw new IllegalArgumentException("工具时间超过数据截止日期");
    }
}
