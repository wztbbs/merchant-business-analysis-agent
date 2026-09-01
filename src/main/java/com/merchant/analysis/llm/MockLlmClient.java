package com.merchant.analysis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性 mock LLM —— 不发起任何网络调用，根据 prompt 中的 TEMPLATE 标记返回固定内容。
 * 仅用于 demo / 离线开发：
 * - TEMPLATE: query_understanding —— 按关键词规则返回意图 JSON（覆盖三个场景的 demo 问题）；
 * - TEMPLATE: conclusion —— 解析 prompt 中 <STEP_RESULTS> 的真实查询结果，用字符串模板拼中文结论。
 */
@Service
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private static final Pattern TEMPLATE = Pattern.compile("TEMPLATE: (\\w+)");
    private static final Pattern QUERY = Pattern.compile("用户问题：(.+)");
    private static final Pattern STEP_RESULTS = Pattern.compile("(?s)<STEP_RESULTS>(.*?)</STEP_RESULTS>");
    private static final Pattern PREV_CONCLUSION = Pattern.compile("(?s)<PREV_CONCLUSION>(.*?)</PREV_CONCLUSION>");
    private static final Pattern FOLLOW_UP_TYPE = Pattern.compile("追问类型：(\\w+)");
    private static final Pattern CHAIN_CONTEXT = Pattern.compile("(?s)<CHAIN_CONTEXT>(.*?)</CHAIN_CONTEXT>");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String chat(String prompt) {
        String template = extract(TEMPLATE, prompt, "unknown");
        return switch (template) {
            case "query_understanding" -> understandingJson(extract(QUERY, prompt, ""), prompt);
            case "conclusion" -> conclusionText(prompt);
            case "followup_answer" -> followUpAnswerText(prompt);
            default -> "{\"error\": \"mock llm: unknown template\"}";
        };
    }

    /**
     * mock 意图识别：确定性关键词规则，无糖可乐 demo 问题走 metric_change_diagnosis + delisting 假设。
     * prompt 含 CHAIN_CONTEXT（候选链尾）时先做 mock 追问判定：省略式问法命中追问模式 →
     * 继承链尾 understanding 组装（只覆盖变化字段），并输出 is_follow_up/ref_run_id/needs_rerun。
     */
    private String understandingJson(String query, String prompt) {
        JsonNode chainTail = firstChainTail(prompt);
        if (chainTail != null) {
            String followUp = tryFollowUp(query, chainTail);
            if (followUp != null) {
                return followUp;
            }
        }
        return appendFollowUpFields(baseUnderstandingJson(query));
    }

    /** mock 追问判定：省略式问法（无完整时间词）命中四类模式之一才判追问，否则返回 null 按新问题处理 */
    private static String tryFollowUp(String query, JsonNode chainTail) {
        if (containsAny(query, "上个月", "上月", "这个月", "本月", "本周", "上周", "今天", "昨天", "去年")) {
            return null;    // 带完整时间词 → 新问题
        }
        String metric = query.matches(".*(订单量|订单数|单量).*") ? "order_count"
                : query.contains("客单价") ? "aov"
                : query.contains("销售额") ? "sales_amount" : null;
        String type = metric != null ? "metric_switch"
                : query.contains("多少") && containsAny(query, "下架", "活动", "流量", "价格", "涨价", "降价", "缺货")
                ? "factor_detail"
                : containsAny(query, "怎么办", "怎么做", "建议", "如何改进") ? "advice"
                : containsAny(query, "什么意思", "解释", "刚才", "上次说") ? "explain"
                : null;
        if (type == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode out =
                    (com.fasterxml.jackson.databind.node.ObjectNode) chainTail.path("understanding").deepCopy();
            if (metric != null) {
                out.put("metric", metric);
            }
            out.put("business_intent", "追问：" + query);
            out.put("is_follow_up", true);
            out.put("follow_up_type", type);
            out.put("ref_run_id", chainTail.path("run_id").asText());
            out.put("needs_rerun", "metric_switch".equals(type) || "time_compare".equals(type));
            return out.toString();
        } catch (Exception e) {
            return null;    // 链上下文不可解析 → 按新问题处理
        }
    }

    /** 取 CHAIN_CONTEXT 里第一条链尾（调用方按时间倒序注入，第一条即最近） */
    private static JsonNode firstChainTail(String prompt) {
        String block = extract(CHAIN_CONTEXT, prompt, "");
        for (String line : block.split("\n")) {
            if (!line.isBlank()) {
                try {
                    return MAPPER.readTree(line.trim());
                } catch (Exception ignored) {
                    // 跳过不可解析行
                }
            }
        }
        return null;
    }

    /** 非追问分支：在意图 JSON 上补追问四字段（默认值） */
    private static String appendFollowUpFields(String baseJson) {
        int idx = baseJson.lastIndexOf('}');
        return baseJson.substring(0, idx)
                + ", \"is_follow_up\": false, \"follow_up_type\": null, \"ref_run_id\": null, \"needs_rerun\": true}";
    }

    /** mock 意图识别（新问题分支）：确定性关键词规则 */
    private String baseUnderstandingJson(String query) {
        String sku = query.contains("无糖可乐") ? "无糖可乐"
                : query.contains("巧克力") ? "巧克力" : null;
        String candidateFactor = query.contains("下架") ? "delisting"
                : query.contains("涨价") || query.contains("降价") ? "price" : null;

        // 指标查询/报表：「是多少 / 查询 / 报表」类
        if (query.contains("是多少") || query.contains("查询") || query.contains("报表")) {
            return """
                    {"business_intent": "查询指标数值", "metric": "sales_amount",
                     "time_range": "last_month", "comparison_baseline": "previous_month",
                     "entity": {"sku": %s}, "candidate_factor": null,
                     "suggest_action": null, "suggest_object": null, "suggest_entity": null,
                     "scene": {"primary_scene": "metric_query_report", "factor_modules": []}}
                    """.formatted(sku == null ? "null" : "\"" + sku + "\"");
        }

        // 因子影响评估：「影响大吗 / 有没有影响」且不含「为什么」
        if ((query.contains("影响")) && !query.contains("为什么")) {
            return """
                    {"business_intent": "评估经营动作的影响", "metric": "sales_amount",
                     "time_range": "last_month", "comparison_baseline": "previous_month",
                     "entity": {"sku": %s}, "candidate_factor": %s,
                     "suggest_action": null, "suggest_object": null, "suggest_entity": null,
                     "scene": {"primary_scene": "factor_impact_evaluation", "factor_modules": [%s]}}
                    """.formatted(quoted(sku), quoted(candidateFactor), candidateFactor == null ? "" : "\"" + candidateFactor + "\"");
        }

        // 指标变化诊断：「为什么 / 下滑 / 异常 / 跌 / 降」类
        if (query.contains("为什么") || query.contains("下滑") || query.contains("异常")
                || query.contains("跌") || query.contains("降")) {
            return """
                    {"business_intent": "诊断销售额下滑原因", "metric": "sales_amount",
                     "time_range": "last_month", "comparison_baseline": "previous_month",
                     "entity": {"sku": %s}, "candidate_factor": %s,
                     "suggest_action": null, "suggest_object": null, "suggest_entity": null,
                     "scene": {"primary_scene": "metric_change_diagnosis",
                               "factor_modules": [%s"campaign", "traffic", "price", "stockout"]}}
                    """.formatted(quoted(sku), quoted(candidateFactor), candidateFactor == null ? "" : "\"delisting\", ");
        }

        // 一个经营分析关键词都没命中 → out_of_scope，不强行归类（对应提示词中的兜底枚举）。
        // suggest_* 切面：mock 只做几个演示级关键词猜测，真实场景由 LLM 按枚举约束输出
        String suggestAction = query.contains("预测") || query.contains("预估") ? "forecast"
                : query.contains("对比") ? "comparison" : null;
        String suggestObject = query.contains("库存") ? "inventory"
                : query.contains("新客") ? "new_customer" : null;
        return """
                {"business_intent": "超出支持范围的问题", "metric": null,
                 "time_range": null, "comparison_baseline": null,
                 "entity": {"sku": null}, "candidate_factor": null,
                 "suggest_action": %s, "suggest_object": %s, "suggest_entity": null,
                 "scene": {"primary_scene": "out_of_scope", "factor_modules": []}}
                """.formatted(quoted(suggestAction), quoted(suggestObject));
    }

    /** mock 结论：解析 step_results JSON，用确定性模板拼中文结论（不追求真智能，只求链路完整） */
    private static String conclusionText(String prompt) {
        // 取最后一个标记块，防止 prompt 正文中提到该标记造成误匹配
        String json = extractLast(STEP_RESULTS, prompt, "");
        if (json.isEmpty()) {
            return "mock 结论：未获取到步骤数据。";
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            StringBuilder sb = new StringBuilder();

            JsonNode meta = root.path("_meta");
            boolean hypothesisBroken = meta.path("hypothesis_broken").asBoolean(false);

            // 1) 变化事实
            JsonNode summary = findWithKey(root, "metric_delta");
            if (summary != null) {
                double cur = summary.path("current_total").asDouble();
                double base = summary.path("baseline_total").asDouble();
                double delta = summary.path("metric_delta").asDouble();
                double pct = base != 0 ? delta / base * 100 : 0;
                sb.append(String.format("【结论】上月销售额 %.2f 元，前月 %.2f 元，环比变化 %.2f 元（%.1f%%），整体呈%s趋势。",
                        cur, base, delta, pct, delta < 0 ? "下滑" : "上升"));
            }

            // 2) 贡献拆解（涨跌方向中性：delta 符号表达方向）
            JsonNode breakdown = findWithKey(root, "top_contributors");
            if (breakdown != null && breakdown.path("top_contributors").isArray()
                    && !breakdown.path("top_contributors").isEmpty()) {
                JsonNode top = breakdown.path("top_contributors").get(0);
                double topDelta = top.path("delta").asDouble();
                sb.append(String.format("贡献度拆解显示，变化贡献最大的单品是「%s」（销售额%s %.2f 元）。",
                        top.path("name").asText(), topDelta < 0 ? "减少" : "增加", Math.abs(topDelta)));
            }

            // 3) 下架因子：假设成立与否
            boolean delistingConfirmed = false;
            if (hypothesisBroken) {
                sb.append(meta.path("hypothesis_note").asText("用户假设不成立：查询期内无下架记录")).append("。");
            } else {
                JsonNode status = findWithKey(root, "delisting_time");
                // 取 contribution_ratio 非空的步骤（sku_sales_history 在多个步骤中复用，仅带 metric_delta 输入的步骤才有贡献率）
                JsonNode impact = findWithNonNull(root, "contribution_ratio");
                if (status != null && !status.path("delisting_time").isNull()) {
                    delistingConfirmed = true;
                    sb.append(String.format("经查证，「%s」于 %s 下架（当前状态：%s）",
                            status.path("sku").asText("该商品"),
                            status.path("delisting_time").asText(), status.path("listing_status").asText("已下架")));
                    if (impact != null) {
                        double salesDelta = impact.path("sales_delta").asDouble();
                        sb.append(String.format("；其基线期日均销售约 %.2f 元，估算下架直接损失约 %.2f 元，占整体变化的 %.1f%%",
                                impact.path("sku_daily_avg_sales").asDouble(),
                                Math.abs(Math.min(salesDelta, 0)),   // 负值部分即损失；上涨则为 0
                                Math.abs(impact.path("contribution_ratio").asDouble()) * 100));
                        JsonNode sub = impact.path("substitution_effect");
                        if (sub.isObject()) {
                            sb.append(String.format("；同品类其他商品销售变化 %.2f 元，替代效应%s",
                                    sub.path("others_delta").asDouble(),
                                    sub.path("others_delta").asDouble() > 0 ? "部分承接了流失需求" : "有限"));
                        }
                    }
                    sb.append("。下架是本次销售额下滑的主要贡献因素。");
                }
            }

            // 4) 流量与活动等次要因素
            JsonNode traffic = findWithKey(root, "traffic_delta");
            if (traffic != null && traffic.path("traffic_delta").isObject()) {
                double uvDelta = traffic.path("traffic_delta").path("visit_uv_delta").asDouble();
                if (uvDelta < 0) {
                    sb.append(String.format("此外，上月进店 UV 环比减少 %.0f，流量下滑为次要因素。", -uvDelta));
                }
            }
            JsonNode campaign = findWithKey(root, "campaign_comparison");
            if (campaign != null && campaign.path("campaign_comparison").isObject()) {
                String comment = campaign.path("campaign_comparison").path("comment").asText("");
                if (!comment.isEmpty()) {
                    sb.append(comment);
                }
            }

            if (delistingConfirmed) {
                sb.append("【建议】优先评估恢复该商品上架或引入同价位替代品；同时补充活动与引流动作，观察一周数据复核。");
            } else {
                sb.append("【建议】以上为当前查询结果；如需进一步归因诊断，可补充“为什么”类问题触发完整诊断链路。");
            }
            return sb.toString();
        } catch (Exception e) {
            return "mock 结论生成失败: " + e.getMessage();
        }
    }

    /** mock 追问回答：基于上次结论与步骤数据，按追问类型取不同侧重（不追求真智能，只求链路完整） */
    private static String followUpAnswerText(String prompt) {
        String prevConclusion = extract(PREV_CONCLUSION, prompt, "");
        String type = extract(FOLLOW_UP_TYPE, prompt, "explain");
        if (prevConclusion.isEmpty()) {
            return "上次分析未产出结论，无法回答该追问。";
        }
        return switch (type) {
            case "factor_detail" -> factorDetailText(prompt, prevConclusion);
            case "advice" -> {
                int idx = prevConclusion.indexOf("【建议】");
                yield idx >= 0 ? prevConclusion.substring(idx)
                        : "结合上次分析：" + prevConclusion;
            }
            default -> "关于上次分析：" + prevConclusion;
        };
    }

    /** factor_detail：从步骤数据里抠该因子的量化数字（如下架损失与占比），抠不到回退到上轮结论 */
    private static String factorDetailText(String prompt, String prevConclusion) {
        String json = extractLast(STEP_RESULTS, prompt, "");
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode impact = findWithNonNull(root, "contribution_ratio");
            if (impact != null) {
                double salesDelta = impact.path("sales_delta").asDouble();
                return String.format("上次分析中，该因素估算直接影响约 %.2f 元，占整体变化的 %.1f%%。",
                        Math.abs(Math.min(salesDelta, 0)),
                        Math.abs(impact.path("contribution_ratio").asDouble()) * 100);
            }
        } catch (Exception ignored) {
            // 数据不可解析时回退到上轮结论
        }
        return "根据上次分析：" + prevConclusion;
    }

    /** 在 step_results 各步骤结果中查找包含指定 key 的第一个步骤对象 */
    private static JsonNode findWithKey(JsonNode stepResults, String key) {
        Iterator<Map.Entry<String, JsonNode>> it = stepResults.fields();
        while (it.hasNext()) {
            JsonNode stepResult = it.next().getValue();
            if (stepResult.isObject() && stepResult.has(key)) {
                return stepResult;
            }
        }
        return null;
    }

    /** 同 findWithKey，但要求该 key 的值非 null */
    private static JsonNode findWithNonNull(JsonNode stepResults, String key) {
        Iterator<Map.Entry<String, JsonNode>> it = stepResults.fields();
        while (it.hasNext()) {
            JsonNode stepResult = it.next().getValue();
            if (stepResult.isObject() && stepResult.has(key) && !stepResult.path(key).isNull()) {
                return stepResult;
            }
        }
        return null;
    }

    private static String extract(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static String extractLast(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        String last = fallback;
        while (m.find()) {
            last = m.group(1).trim();
        }
        return last;
    }

    private static String quoted(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private static boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
