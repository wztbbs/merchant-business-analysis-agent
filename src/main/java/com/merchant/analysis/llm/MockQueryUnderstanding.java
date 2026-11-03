package com.merchant.analysis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.merchant.analysis.time.TimeSpec;
import com.merchant.analysis.understanding.QueryTask;
import java.util.*;
import java.util.regex.*;

/** 离线演示的确定性替身；正式语义理解仍由配置的 LLM 完成。 */
final class MockQueryUnderstanding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CONTEXT = Pattern.compile("(?s)<CHAIN_CONTEXT>(.*?)</CHAIN_CONTEXT>");
    private static final Pattern TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\s*(?:到|至|~)\\s*\\d{4}-\\d{2}-\\d{2}|最近\\s*\\d+\\s*天|上个月|这个月|上季度|本季度|下个月|上月|本月|昨日|昨天|前天|上周|本周|这周|去年|今年|今天|明天|下周");

    static String respond(String query, String prompt) {
        try {
            List<JsonNode> chains = chains(prompt);
            boolean referential = query.matches("(?s)^(那|这几个|这些|它们|刚才|上次).*")
                    || has(query, "怎么办", "解释", "什么意思", "影响到底多少");
            JsonNode anchor = chains.size() == 1 ? chains.get(0) : null;
            if (anchor == null) {
                List<JsonNode> named = chains.stream().filter(c -> !c.path("understanding").path("entity").path("sku").isNull()
                        && !c.path("understanding").path("entity").path("sku").asText().isBlank()
                        && query.contains(c.path("understanding").path("entity").path("sku").asText())).toList();
                if (named.size() == 1) anchor = named.get(0);
            }
            boolean follow = referential && anchor != null;
            ObjectNode out = follow ? (ObjectNode) anchor.path("understanding").deepCopy() : JSON.createObjectNode();
            out.remove(List.of("task", "routing", "resolved_time"));
            boolean domain = follow || referential || has(query, "销售", "销量", "营业", "订单", "单量", "客单", "商品", "单品", "品类", "缺货", "库存", "复购", "新客", "流量", "下架", "上架", "价格", "涨价", "降价", "活动", "经营", "利润", "成本", "毛利");
            List<String> products = new ArrayList<>();
            for (String name : List.of("无糖可乐", "巧克力", "橙汁", "冰红茶")) if (query.contains(name)) products.add(name);
            if (follow && products.isEmpty() && anchor.path("understanding").path("entity").path("sku").isTextual())
                products.add(anchor.path("understanding").path("entity").path("sku").asText());
            String metric = has(query, "复购") ? "repeat_purchase" : has(query, "库存") ? "inventory"
                    : has(query, "新客") ? "new_customer" : has(query, "利润") ? "profit"
                    : has(query, "销量", "多少件") ? "units_sold"
                    : has(query, "客单价") ? "aov" : has(query, "订单", "单量") ? "order_count"
                    : has(query, "销售", "营业") ? "sales_amount"
                    : follow ? anchor.path("understanding").path("metric").asText("sales_amount")
                    : has(query, "缺货") && !has(query, "影响") ? "stockout"
                    : has(query, "价格", "涨价", "降价") && !has(query, "影响") ? "price"
                    : has(query, "上架", "下架") && !has(query, "影响") ? "listing_status"
                    : has(query, "流量") ? "traffic" : "sales_amount";
            String action = has(query, "建议", "怎么办", "怎么做") ? "advice" : has(query, "预测", "预估") ? "forecast" : has(query, "为什么", "原因") ? "diagnosis"
                    : has(query, "影响") ? "evaluation" : has(query, "对比", "比较", "变化率", "增长率", "倍数", "占比") ? "comparison" : "query";
            String candidate = has(query, "下架") ? "delisting" : has(query, "涨价", "降价") ? "price"
                    : has(query, "缺货") && action.equals("evaluation") ? "stockout"
                    : follow && anchor.path("understanding").path("candidate_factor").isTextual()
                    ? anchor.path("understanding").path("candidate_factor").asText() : null;
            String primary = action.equals("diagnosis") ? "metric_change_diagnosis" : action.equals("evaluation")
                    ? "factor_impact_evaluation" : Set.of("query", "comparison").contains(action) ? "metric_query_report" : "other";
            String scope = products.size() > 1 ? "multi_sku" : !products.isEmpty() ? "sku" : "shop";
            if (action.equals("diagnosis") && has(query, "跟", "有关", "是不是")) scope = "shop";
            String scene = null;
            if (has(query, "品类")) { scene = "category_structure_review"; scope = "category"; }
            else if (has(query, "商品", "单品") && has(query, "变化最大", "变化最多", "下降最多", "销售最多", "贡献最大", "挑", "前几")) {
                scene = "top_sku_drilldown"; scope = "dynamic_skus";
            } else if (has(query, "缺货") && has(query, "下降", "下滑", "跌") && has(query, "哪些", "商品里", "发生过")) {
                scene = "stockout_sales_crosscheck"; scope = "dynamic_skus";
            } else if (products.size() > 1) scene = "multi_sku_review";

            List<TimeSpec> times = new ArrayList<>();
            Matcher dates = TIME.matcher(query);
            boolean unavailable = false;
            while (dates.find()) {
                String expression = dates.group();
                if (has(expression, "明天", "下个月", "下周", "今天")) { unavailable = true; continue; }
                times.add(time(expression));
            }
            unavailable |= has(query, "实时", "小时", "分钟");
            if (!follow || !times.isEmpty()) {
                out.set("time_range", JSON.valueToTree(times.isEmpty() ? TimeSpec.calendar("month", -1) : times.get(0)));
                out.putNull("comparison_baseline");
            }
            String followType = null;
            boolean rerun = true;
            String resolved = query;
            boolean group = has(query, "这几个", "这些商品", "那几个", "它们", "刚才那些");
            List<QueryTask.FactReference> refs = new ArrayList<>();
            String question = referential && anchor == null ? "请明确要承接哪次分析，以及所指的商品或指标。" : null;
            if (follow) {
                followType = !times.isEmpty() ? "time_compare" : group ? "result_reference"
                        : has(query, "订单", "单量", "客单价", "销售额") ? "metric_switch"
                        : has(query, "怎么办", "建议") ? "advice" : has(query, "影响", "多少") ? "factor_detail" : "explain";
                rerun = Set.of("time_compare", "metric_switch", "result_reference").contains(followType)
                        || has(query, "合计", "均值", "平均", "筛选", "比较", "哪些");
                if (group) {
                    List<JsonNode> collections = new ArrayList<>();
                    anchor.path("facts").forEach(fact -> { if (fact.path("shape").asText().equals("collection")) collections.add(fact); });
                    JsonNode selected = collections.stream().filter(f -> f.path("path").asText().endsWith(".top_contributors")).findFirst().orElse(null);
                    if (selected == null && collections.size() == 1) selected = collections.get(0);
                    if (selected == null) question = "请明确所指的商品集合。";
                    else refs.add(new QueryTask.FactReference(anchor.path("run_id").asText(), selected.path("path").asText()));
                    scope = "dynamic_skus";
                    if (has(query, "缺货")) metric = "stockout";
                }
                if (!rerun) action = followType.equals("advice") ? "advice" : "explain";
                else if (followType.equals("metric_switch")) {
                    String previousScene = anchor.path("understanding").path("scene").path("primary_scene").asText();
                    if (previousScene.equals("metric_change_diagnosis")) { action = "diagnosis"; scope = "shop"; }
                    primary = previousScene;
                }
                String inheritedPeriod = period(anchor.path("understanding").path("resolved_time").path("current"));
                resolved = (action.equals("diagnosis") ? "诊断" : action.equals("advice") ? "根据既有分析给出建议：" : !rerun ? "解释既有分析：" : "查询")
                        + (scope.equals("shop") ? "全店" : String.join("、", products)) + "的" + metricName(metric)
                        + "；观察期：" + inheritedPeriod + "；本轮要求：" + query;
                if (!refs.isEmpty()) resolved += "；商品集合沿用所引用的历史结果";
            }
            if (unavailable) {
                question = "目前仅有截至昨日的日数据，请指定可查询的完整业务日期。";
                out.putNull("time_range"); out.putNull("comparison_baseline");
            }
            List<QueryTask.Requirement> requirements = new ArrayList<>();
            if (domain) {
                List<String> entities = scope.equals("shop") || scope.equals("dynamic_skus") ? List.of() : products;
                requirements.add(new QueryTask.Requirement(action, metric, scope, entities));
                if (times.size() > 1) {
                    requirements.clear();
                    for (TimeSpec spec : times) requirements.add(new QueryTask.Requirement(action, metric, scope, entities, spec, null));
                } else {
                    if (has(query, "销售", "营业") && has(query, "订单", "单量") && !metric.equals("sales_amount"))
                        requirements.add(new QueryTask.Requirement("query", "sales_amount", scope, entities));
                    if (has(query, "缺货") && !metric.equals("stockout")) requirements.add(new QueryTask.Requirement("query", "stockout", scope, entities));
                    if (has(query, "价格") && !metric.equals("price")) requirements.add(new QueryTask.Requirement("query", "price", scope, entities));
                }
            }
            if (follow && !rerun) primary = anchor.path("understanding").path("scene").path("primary_scene").asText("other");
            out.put("business_intent", resolved);
            if (domain) out.put("metric", metric); else { out.putNull("metric"); out.putNull("time_range"); out.putNull("comparison_baseline"); }
            out.set("entity", JSON.valueToTree(Collections.singletonMap("sku", products.size() == 1 ? products.get(0) : null)));
            if (candidate == null) out.putNull("candidate_factor"); else out.put("candidate_factor", candidate);
            out.putNull("suggest_action"); out.putNull("suggest_object"); out.putNull("suggest_entity");
            out.set("scene", JSON.valueToTree(Map.of("primary_scene", domain ? primary : "out_of_scope", "factor_modules", List.of())));
            out.set("task", JSON.valueToTree(new QueryTask(null, resolved, domain, requirements, refs, question != null, question)));
            if (domain && Set.of("metric_change_diagnosis", "factor_impact_evaluation", "metric_query_report").contains(primary)) out.put("matched_playbook", primary);
            else out.putNull("matched_playbook");
            if (scene != null) out.put("matched_react_scene", scene); else out.putNull("matched_react_scene");
            out.put("is_follow_up", follow);
            if (follow) { out.put("follow_up_type", followType); out.put("ref_run_id", anchor.path("run_id").asText()); }
            else { out.putNull("follow_up_type"); out.putNull("ref_run_id"); }
            out.put("needs_rerun", rerun);
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("mock 意图理解失败", e); }
    }
    private static String metricName(String metric) {
        return switch (metric) {
            case "sales_amount" -> "销售额";
            case "order_count" -> "订单量";
            case "aov" -> "客单价";
            case "stockout" -> "缺货记录";
            default -> metric;
        };
    }
    private static String period(JsonNode range) {
        if (!range.path("start").isTextual()) return "沿用上次分析的观察期";
        return range.path("start").asText() + "至" + java.time.LocalDate.parse(range.path("endExclusive").asText()).minusDays(1);
    }
    private static TimeSpec time(String expression) {
        if (expression.matches("\\d{4}-.*")) {
            Matcher dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(expression);
            dates.find(); String start = dates.group(); dates.find(); return TimeSpec.dates(start, dates.group());
        }
        if (expression.startsWith("最近")) return TimeSpec.recentDays(Integer.parseInt(expression.replaceAll("[^0-9]", "")));
        return switch (expression) {
            case "昨天", "昨日" -> TimeSpec.calendar("day", -1);
            case "前天" -> TimeSpec.calendar("day", -2);
            case "上周" -> TimeSpec.calendar("week", -1);
            case "本周", "这周" -> TimeSpec.calendar("week", 0);
            case "这个月", "本月" -> TimeSpec.calendar("month", 0);
            case "上季度" -> TimeSpec.calendar("quarter", -1);
            case "本季度" -> TimeSpec.calendar("quarter", 0);
            case "去年" -> TimeSpec.calendar("year", -1);
            case "今年" -> TimeSpec.calendar("year", 0);
            default -> TimeSpec.calendar("month", -1);
        };
    }
    private static List<JsonNode> chains(String prompt) throws Exception {
        Matcher context = CONTEXT.matcher(prompt);
        List<JsonNode> result = new ArrayList<>();
        if (context.find()) for (String line : context.group(1).split("\n")) if (!line.isBlank()) result.add(JSON.readTree(line));
        return result;
    }
    private static boolean has(String value, String... words) { return Arrays.stream(words).anyMatch(value::contains); }
}
