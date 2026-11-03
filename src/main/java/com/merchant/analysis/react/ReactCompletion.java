package com.merchant.analysis.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityCatalog;
import com.merchant.analysis.tool.ToolCatalog;
import com.merchant.analysis.time.*;
import com.merchant.analysis.understanding.QueryTask;
import org.springframework.stereotype.Component;
import java.util.*;

/** 检查证据存在、指标来源、查询时间和显式实体；不能把裸数据冒充比较结果。 */
@Component
public class ReactCompletion {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TimeResolver time;
    private final ToolCatalog tools;
    public ReactCompletion(TimeResolver time) { this(time, new ToolCatalog(new CapabilityCatalog())); }
    @org.springframework.beans.factory.annotation.Autowired
    public ReactCompletion(TimeResolver time, ToolCatalog tools) { this.time = time; this.tools = tools; }
    private record Fact(String tool, String field, Map<String, Object> arguments, Object value) {}

    public List<ReactState.Coverage> check(ReactAction action, ReactState state, AnalysisContext ctx) {
        List<QueryTask.Requirement> requests = ctx.understanding().task().requirements();
        if (action.requirements().size() != requests.size()) throw new IllegalArgumentException("finish 必须逐项覆盖全部子需求");
        Set<Integer> seen = new HashSet<>();
        List<ReactState.Coverage> checked = new ArrayList<>();
        for (var claim : action.requirements()) {
            if (claim.index() < 0 || claim.index() >= requests.size() || !seen.add(claim.index())) throw new IllegalArgumentException("完成度 index 缺失、重复或越界");
            List<Fact> facts = new ArrayList<>(); boolean calculated = false, viewed = true;
            for (String path : claim.evidence()) {
                Object value = ReactViews.reference(path, ctx);
                if (value instanceof List<?> rows && rows.size() > 10) viewed &= fullyViewed(path, rows.size(), state);
                if (value != null) facts.addAll(origins(path, state, ctx, new HashSet<>()));
                String id = path.substring(0, path.indexOf('.'));
                calculated |= state.calls.stream().anyMatch(c -> c.callId().equals(id)
                        && com.merchant.analysis.calculation.CalculationEngine.OPERATORS.contains(c.invocation().name()));
            }
            var request = requests.get(claim.index());
            List<Fact> matching = facts.stream().filter(f -> supports(request, f) && matchesTime(request, f, ctx)).toList();
            boolean complete = !matching.isEmpty() && viewed;
            if ("stockout_sales_crosscheck".equals(state.scene) && request.metric().equals("sales_amount")) {
                for (String evidence : claim.evidence()) {
                    String id = evidence.substring(0, evidence.indexOf('.'));
                    var selector = state.calls.stream().filter(c -> c.callId().equals(id) && c.invocation().name().equals("stockout_query")).findFirst().orElse(null);
                    if (selector != null && ctx.lookup(id, "stockout_events") instanceof List<?> rows && rows.isEmpty()
                            && matchesTime(request, new Fact("stockout_query", "stockout_events", selector.invocation().arguments(), rows), ctx)) complete = true;
                }
            }
            if (request.action().equals("comparison")) complete &= calculated;
            if (request.scope().equals("sku") || request.scope().equals("multi_sku"))
                complete &= matching.stream().anyMatch(f -> !f.tool().equals("sales_summary_query"));
            if (!request.entities().isEmpty() && Set.of("sku", "multi_sku").contains(request.scope()))
                complete &= request.entities().stream().allMatch(entity -> matching.stream().anyMatch(f ->
                        entity.equals(f.arguments().get("sku")) || ReactViews.json(f.value()).contains("\"" + entity + "\"")));
            if (state.scene != null && state.scene.equals("stockout_sales_crosscheck") && request.metric().equals("sales_amount"))
                complete &= checkedEveryStockoutSku(state, ctx);
            String status = claim.status();
            String reason = claim.reason();
            if (status.equals("completed") && !complete) {
                status = matching.isEmpty() ? "missing" : "partial";
                reason = "证据不足以证明该指标、实体、时间范围或比较任务已完整完成";
            }
            checked.add(new ReactState.Coverage(claim.index(), status, claim.evidence(), reason));
        }
        checked.sort(Comparator.comparingInt(ReactState.Coverage::index));
        return List.copyOf(checked);
    }
    public boolean anyCapability(AnalysisContext ctx) {
        return ctx.understanding().task().requirements().stream().anyMatch(r -> !r.action().equals("forecast") && tools.canSupply(r.metric(), r.scope()));
    }
    public List<ReactState.Origin> lineage(String path, ReactState state, AnalysisContext ctx) {
        return origins(path, state, ctx, new HashSet<>()).stream()
                .map(f -> new ReactState.Origin(f.tool(), f.field(), f.arguments())).distinct().toList();
    }

    private static boolean fullyViewed(String path, int size, ReactState state) {
        List<int[]> ranges = new ArrayList<>();
        for (String key : state.inspected) if (key.startsWith(path + ":")) {
            String[] parts = key.substring(path.length()+1).split(":");
            ranges.add(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[0])+Integer.parseInt(parts[1])});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0])); int end = 10;
        for (int[] range : ranges) { if (range[0] > end) break; end = Math.max(end, range[1]); }
        return end >= size;
    }
    private List<Fact> origins(String path, ReactState state, AnalysisContext ctx, Set<String> visited) {
        String id = path.substring(0, path.indexOf('.'));
        if (!visited.add(path)) return List.of();
        var call = state.calls.stream().filter(c -> c.callId().equals(id)).findFirst().orElse(null);
        if (call != null && com.merchant.analysis.calculation.CalculationEngine.OPERATORS.contains(call.invocation().name())) {
            List<Fact> result = new ArrayList<>();
            for (String ref : call.invocation().references()) result.addAll(origins(ref, state, ctx, visited));
            return result;
        }
        if (call == null) {
            String key = state.lineage.keySet().stream().filter(p -> path.equals(p) || path.startsWith(p + "."))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            if (key != null) return state.lineage.get(key).stream()
                    .map(origin -> new Fact(origin.tool(), origin.field(), origin.arguments(), ReactViews.reference(path, ctx))).toList();
        }
        String field = path.substring(path.indexOf('.') + 1).split("\\.")[0];
        Object value = ReactViews.reference(path, ctx);
        Map<String, Object> args = call == null ? state.originArguments.getOrDefault(id, Map.of()) : call.invocation().arguments();
        List<String> names = call == null ? state.origins.getOrDefault(id, List.of()) : List.of(call.invocation().name());
        return names.stream().map(name -> new Fact(name, field, args, value)).toList();
    }
    private boolean supports(QueryTask.Requirement request, Fact fact) {
        var schema = tools.definition(fact.tool()).outputSchema().path("properties").path(fact.field());
        if (request.action().equals("forecast")) {
            boolean forecasting = false;
            for (var action : schema.path("x-actions")) if (action.asText().equals("forecast")) forecasting = true;
            if (!forecasting) return false;
        }
        boolean metric = false, scope = false;
        for (var value : schema.path("x-metrics")) if (value.asText().equals(request.metric())) metric = true;
        for (var value : schema.path("x-scopes")) if (value.asText().equals(request.scope())) scope = true;
        return metric && scope;
    }
    private boolean matchesTime(QueryTask.Requirement request, Fact fact, AnalysisContext ctx) {
        if (!fact.arguments().containsKey("time_range")) return false;
        var u = ctx.understanding();
        ResolvedTime expected = request.time_range() == null && request.comparison_baseline() == null ? u.resolved_time()
                : time.resolvePeriods(request.time_range() == null ? u.time_range() : request.time_range(),
                    request.comparison_baseline() == null ? u.comparison_baseline() : request.comparison_baseline(), u.resolved_time().context());
        DateRange actual = JSON.convertValue(fact.arguments().get("time_range"), DateRange.class);
        if (!expected.current().equals(actual)) return false;
        return !fact.arguments().containsKey("baseline") || expected.baseline().equals(JSON.convertValue(fact.arguments().get("baseline"), DateRange.class));
    }
    private static boolean checkedEveryStockoutSku(ReactState state, AnalysisContext ctx) {
        Set<String> names = new HashSet<>(), checked = new HashSet<>();
        for (var call : state.calls) {
            if (call.invocation().name().equals("stockout_query")) {
                Object value = ctx.lookup(call.callId(), "stockout_events");
                if (value instanceof List<?> rows) for (Object row : rows) names.add(String.valueOf(((Map<?, ?>) row).get("name")));
            }
            if (call.invocation().name().equals("sku_sales_history") && ctx.lookup(call.callId(), "sku_total_sales") != null) checked.add(String.valueOf(call.invocation().arguments().get("sku")));
        }
        return checked.containsAll(names);
    }
}
