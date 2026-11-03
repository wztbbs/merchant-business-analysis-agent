package com.merchant.analysis.react;

import com.merchant.analysis.eval.GroundingChecker;
import com.merchant.analysis.execution.AnalysisContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class ReactAnswers {
    private final String template;
    private final GroundingChecker grounding;
    public ReactAnswers(@Value("classpath:prompts/react_answer.txt") Resource resource, GroundingChecker grounding) {
        this.grounding = grounding;
        try { template = resource.getContentAsString(StandardCharsets.UTF_8); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String prompt(ReactState state, AnalysisContext ctx) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", ctx.understanding().task()); input.put("time", ctx.understanding().resolved_time());
        input.put("status", state.status); input.put("reason", state.reason); input.put("coverage", state.coverage);
        input.put("FACTS", ReactViews.preview(ctx.stepResults()));
        return template.replace("{{input}}", ReactViews.json(input));
    }
    public boolean claimsComplete(String answer) {
        String affirmative = answer.replaceAll("未全部完成|没有全部完成|尚未全部完成|未完整完成|尚未完整完成", "");
        return affirmative.matches("(?s).*(已全部完成|已完整完成|所有问题均已完成|全部需求已完成).*");
    }
    public boolean grounded(String answer, AnalysisContext ctx) {
        if (answer == null || answer.isBlank()) return false;
        return grounding.check(answer, ctx.stepResults(), false, ctx.understanding().task().original_query(),
                ctx.understanding().metric(), ctx.understanding().skuOrNull()).stream()
                .filter(check -> Set.of("number_grounding", "entity_grounding").contains(check.name()))
                .allMatch(GroundingChecker.CheckResult::passed);
    }
    /** 模型超时或答案校验失败时由代码引用已有事实收尾，不再次请求模型。 */
    public String fallback(ReactState state, AnalysisContext ctx) {
        List<String> lines = new ArrayList<>();
        lines.add(state.status == ReactStatus.COMPLETED ? "已取得本次查询结果。" : "本次分析未全部完成：" + state.reason + "。");
        var time = ctx.understanding().resolved_time();
        lines.add("观察期为 " + time.current().start() + " 至 " + java.time.LocalDate.parse(time.current().endExclusive()).minusDays(1)
                + "，数据截至 " + time.context().data_through() + "。");
        for (Map<String, Object> output : ctx.stepResults().values()) {
            if (lines.size() >= 18) { lines.add("其余数据保留在本次分析明细中。"); break; }
            if (output.containsKey("current_total")) lines.add("全店销售额：观察期 " + output.get("current_total") + " 元，基线期 " + output.get("baseline_total") + " 元。");
            if (output.containsKey("sku_total_sales") && output.get("sku_total_sales") == null) lines.add("未找到指定商品的销售数据，不能将缺失视为零。");
            else if (output.containsKey("sku_total_sales")) lines.add("商品" + output.getOrDefault("sku", "（未识别）")
                    + "：观察期销售额 " + output.get("sku_total_sales") + " 元，销售变化 " + output.get("sales_delta") + " 元。");
            rows(lines, output.get("category_breakdown"), "category");
            rows(lines, output.get("top_contributors"), "name");
            if (output.get("stockout_events") instanceof List<?> events)
                lines.add(events.isEmpty() ? "观察期未查询到缺货事件。" : "观察期查到缺货事件，具体商品及时间保留在分析明细中。");
            if (output.containsKey("value")) lines.add("已计算结果：" + output.get("value") + (output.containsKey("percent") ? "（百分数为 " + output.get("percent") + "%）" : "") + "。");
        }
        if (ctx.stepResults().isEmpty()) lines.add("没有取得足以回答该问题的经营数据，无法提供数值结论。");
        return String.join("\n", lines);
    }
    private static void rows(List<String> lines, Object value, String label) {
        if (!(value instanceof List<?> rows)) return;
        if (rows.isEmpty()) { lines.add("本次未返回对应销售明细。"); return; }
        for (Object row : rows.stream().limit(3).toList()) {
            Map<?, ?> item = (Map<?, ?>) row;
            lines.add(item.get(label) + "：观察期销售额 " + item.get("current_total") + " 元，基线期 " + item.get("baseline_total") + " 元，变化 " + item.get("delta") + " 元。");
        }
        if (rows.size() > 3) lines.add("这里只展示部分明细。");
    }
}
