package com.merchant.analysis.llm;

import com.fasterxml.jackson.databind.*;
import com.merchant.analysis.time.*;
import java.util.*;
import java.util.regex.*;

/** 离线演示：只根据已加载定义和真实观察值产生动作，不伪造查询结果。 */
final class MockReact {
    private static final ObjectMapper JSON = new ObjectMapper();
    static String decision(String prompt) {
        try {
            JsonNode input = block(prompt, "REACT_INPUT");
            Set<String> loaded = new HashSet<>(); input.path("loaded_tools").forEach(t -> loaded.add(t.path("name").asText()));
            Set<String> needed = new LinkedHashSet<>();
            for (var request : input.path("task").path("requirements")) {
                String tool = tool(request, input); if (tool != null) needed.add(tool);
                if (request.path("scope").asText().equals("dynamic_skus") && request.path("metric").asText().equals("sales_amount"))
                    needed.add(input.path("scene_id").asText().equals("stockout_sales_crosscheck") ? "stockout_query" : "sku_sales_breakdown");
                if (tool != null && request.path("action").asText().equals("comparison")) needed.add(request.path("scope").asText().equals("multi_sku") ? "compare" : "relative_change");
            }
            if (input.path("scene_id").asText().equals("category_structure_review") && input.path("task").path("resolved_query").asText().contains("最大")) needed.add("max");
            List<String> missing = needed.stream().filter(name -> !loaded.contains(name)).limit(input.path("budget").path("max_load_batch").asInt(5)).toList();
            if (!missing.isEmpty()) return json(Map.of("action", "load_tools", "names", missing));
            List<Map<String, Object>> coverage = new ArrayList<>(); int index = 0;
            for (JsonNode request : input.path("task").path("requirements")) {
                String name = tool(request, input), scope = request.path("scope").asText();
                List<String> evidence = new ArrayList<>();
                if (name == null) { coverage.add(coverage(index++, "missing", evidence, "现有工具未提供该指标或查询范围")); continue; }
                List<JsonNode> base = new ArrayList<>();
                if (scope.equals("dynamic_skus") && name.equals("sku_sales_history")) {
                    String selector = input.path("scene_id").asText().equals("stockout_sales_crosscheck") ? "stockout_query" : "sku_sales_breakdown";
                    JsonNode selected = find(input, selector, request, null);
                    if (selected == null) return call(selector, args(request));
                    String collection = selector.equals("stockout_query") ? "stockout_events" : "top_contributors";
                    JsonNode rows = rows(result(input, selected).path(collection)); Set<String> seen = new HashSet<>(); int count=0;
                    for (int i=0;i<rows.size();i++) {
                        JsonNode row = rows.get(i); String sku = row.path("name").asText();
                        if (!seen.add(sku)) continue;
                        if (selector.equals("sku_sales_breakdown") && input.path("task").path("original_query").asText().contains("下降") && row.path("delta").asDouble() >= 0) continue;
                        if (selector.equals("sku_sales_breakdown") && count++ >= 3) break;
                        JsonNode found = find(input, name, request, sku);
                        if (found == null) {
                            var arguments = args(request); arguments.put("sku", ref(id(selected)+"."+collection+"."+i+".name")); return call(name, arguments);
                        }
                        base.add(found);
                    }
                    if (base.isEmpty()) evidence.add(id(selected)+"."+collection);
                } else if (scope.equals("sku") || scope.equals("multi_sku")) {
                    for (JsonNode entity : request.path("entities")) {
                        JsonNode found = find(input, name, request, entity.asText());
                        if (found == null) { var arguments=args(request); arguments.put("sku", entity.asText()); return call(name, arguments); }
                        base.add(found);
                    }
                } else {
                    JsonNode found = find(input, name, request, null);
                    if (found == null) return call(name, args(request));
                    base.add(found);
                }
                for (JsonNode found : base) evidence.add(id(found)+"."+field(name, request.path("metric").asText()));
                boolean complete = true;
                if (request.path("action").asText().equals("comparison")) {
                    String calculation = scope.equals("multi_sku") ? "compare" : "relative_change";
                    String left=null, right=null;
                    if (scope.equals("multi_sku") && base.size() >= 2) { left=id(base.get(0))+".sales_delta"; right=id(base.get(1))+".sales_delta"; }
                    else if (name.equals("sales_summary_query") && !base.isEmpty()) {
                        String metric=request.path("metric").asText();
                        left=id(base.get(0))+"."+(metric.equals("order_count")?"current_orders":metric.equals("aov")?"current_aov":"current_total");
                        right=id(base.get(0))+"."+(metric.equals("order_count")?"baseline_orders":metric.equals("aov")?"baseline_aov":"baseline_total");
                    }
                    if (left != null) {
                        JsonNode calculated = calculation(input, calculation, left, right);
                        if (calculated == null) {
                            Object baseline = value(input, right);
                            if (calculation.equals("relative_change") && (baseline == null || new java.math.BigDecimal(baseline.toString()).signum() <= 0)) complete=false;
                            else return call(calculation, Map.of("input", Map.of(calculation.equals("compare")?"left":"current", ref(left), calculation.equals("compare")?"right":"baseline", ref(right))));
                        } else evidence.add(id(calculated)+".value");
                    } else complete=false;
                }
                if (needed.contains("max") && name.equals("category_sales_breakdown") && !base.isEmpty()) {
                    String collection=id(base.get(0))+".category_breakdown";
                    JsonNode calculated=calculation(input,"max",collection,null);
                    if(calculated==null) return call("max", Map.of("input", Map.of("kind","field","collection",ref(collection),"field","current_total")));
                    evidence.add(id(calculated)+".value"); evidence.add(id(calculated)+".item");
                }
                coverage.add(coverage(index++, complete?"completed":"partial", evidence, complete?"已取得对应查询或计算结果":"缺少可比较的基线或完整结果"));
            }
            boolean all=coverage.stream().allMatch(c->c.get("status").equals("completed"));
            boolean any=coverage.stream().anyMatch(c->!c.get("status").equals("missing"));
            return json(Map.of("action","finish","status",all?"COMPLETED":any?"PARTIAL":"CAPABILITY_MISSING", "requirements",coverage,
                    "reason",all?"已按需求取得数据与计算结果":"现有数据或工具不能完整提供全部所需结果"));
        } catch (Exception e) { throw new IllegalStateException("mock ReAct 动作构造失败",e); }
    }
    static String answer(String prompt) {
        try {
            JsonNode input=block(prompt,"REACT_ANSWER_INPUT"); List<String> lines=new ArrayList<>();
            lines.add(input.path("status").asText().equals("COMPLETED")?"查询结果如下：":"本次分析未全部完成："+input.path("reason").asText()+"。");
            lines.add("观察期 "+input.path("time").path("current").path("start").asText()+" 至 "+java.time.LocalDate.parse(input.path("time").path("current").path("endExclusive").asText()).minusDays(1)
                    +"，数据截至 "+input.path("time").path("context").path("data_through").asText()+"。");
            input.path("FACTS").fields().forEachRemaining(entry->{ JsonNode out=entry.getValue();
                if(out.has("current_total")) lines.add("全店销售额：观察期 "+out.path("current_total").asText()+" 元，基线期 "+out.path("baseline_total").asText()+" 元。");
                if(out.has("sku_total_sales")) lines.add(out.path("sku_total_sales").isNull()?"未找到指定商品销售数据。":out.path("sku").asText("该商品")+"：观察期销售额 "+out.path("sku_total_sales").asText()+" 元，基线期 "+out.path("sku_baseline_sales").asText()+" 元，变化 "+out.path("sales_delta").asText()+" 元。");
                for(String key:List.of("category_breakdown","top_contributors")) for(JsonNode row:rows(out.path(key))) lines.add(row.path(key.equals("category_breakdown")?"category":"name").asText()+"：观察期销售额 "+row.path("current_total").asText()+" 元，基线期 "+row.path("baseline_total").asText()+" 元，变化 "+row.path("delta").asText()+" 元。");
                if(out.has("stockout_events")) {
                    JsonNode rows=rows(out.path("stockout_events"));
                    if(rows.isEmpty()) lines.add("观察期未查到缺货记录。");
                    else for(JsonNode row:rows) lines.add(row.path("name").asText()+"："+row.path("event_time").asText()+" 记录了"+(row.path("event_type").asText().endsWith("start")?"缺货开始":"缺货结束")+"。");
                }
                if(out.has("price_changes")) for(JsonNode row:rows(out.path("price_changes"))) lines.add("商品 "+row.path("sku_id").asText()+"：基线均价 "+row.path("baseline_avg_price").asText()+" 元，观察期均价 "+row.path("current_avg_price").asText()+" 元。");
                if(out.has("listing_status")) lines.add(out.path("sku").asText("该商品")+"观察期末状态："+out.path("listing_status").asText()+"。");
                if(out.has("value")) lines.add("计算结果："+out.path("value").asText()+(out.has("percent")?"，对应百分数 "+out.path("percent").asText()+"%":"")+"。");
            });
            if(input.path("FACTS").isEmpty()) lines.add("现有工具没有提供该指标的数据，无法给出数值结论。");
            if(input.path("FACTS").toString().contains("\"truncated\":true")) lines.add("这里只展示部分明细。");
            return String.join("\n",lines);
        }catch(Exception e){throw new IllegalStateException("mock ReAct 回答失败",e);}
    }
    private static String tool(JsonNode r,JsonNode input){
        String scope=r.path("scope").asText();
        return switch(r.path("metric").asText()){
            case "sales_amount" -> scope.equals("category")?"category_sales_breakdown":Set.of("sku","multi_sku","dynamic_skus").contains(scope)?"sku_sales_history":"sales_summary_query";
            case "order_count","aov" -> scope.equals("shop")?"sales_summary_query":null;
            case "stockout" -> "stockout_query";
            case "price" -> "price_change_query";
            case "listing_status" -> "sku_status_query";
            case "traffic","conversion_rate" -> "traffic_conversion_query";
            case "campaign" -> "campaign_query";
            default -> null;
        };
    }
    private static Map<String,Object> args(JsonNode request){
        Map<String,Object> args=new LinkedHashMap<>();
        if(!request.path("time_range").isNull()&&!request.path("time_range").isMissingNode())args.put("time_range",JSON.convertValue(request.get("time_range"),Object.class));
        if(!request.path("comparison_baseline").isNull()&&!request.path("comparison_baseline").isMissingNode())args.put("baseline",JSON.convertValue(request.get("comparison_baseline"),Object.class));
        return args;
    }
    private static JsonNode find(JsonNode input,String name,JsonNode request,String sku){
        for(JsonNode call:input.path("calls"))if(call.path("invocation").path("name").asText().equals(name)
                &&(sku==null||call.path("invocation").path("arguments").path("sku").asText().equals(sku))){
            JsonNode arguments=call.path("invocation").path("arguments");
            ResolvedTime expected=JSON.convertValue(input.path("time"),ResolvedTime.class);
            if(request.path("time_range").isObject())expected=new TimeResolver().resolvePeriods(JSON.convertValue(request.get("time_range"),TimeSpec.class),
                    request.path("comparison_baseline").isObject()?JSON.convertValue(request.get("comparison_baseline"),TimeSpec.class):null,expected.context());
            if(arguments.path("time_range").equals(JSON.valueToTree(expected.current())))return call;
        }return null;
    }
    private static JsonNode calculation(JsonNode input,String name,String left,String right){
        for(JsonNode call:input.path("calls"))if(call.path("invocation").path("name").asText().equals(name)){
            List<String> refs=new ArrayList<>();call.path("invocation").path("references").forEach(r->refs.add(r.asText()));
            if(refs.contains(left)&&(right==null||refs.contains(right)))return call;
        }return null;
    }
    private static String field(String name,String metric){return switch(name){
        case "sales_summary_query" -> metric.equals("order_count")?"current_orders":metric.equals("aov")?"current_aov":"current_total";
        case "sku_sales_history" -> "sku_total_sales";
        case "category_sales_breakdown" -> "category_breakdown";
        case "stockout_query" -> "stockout_events";
        case "price_change_query" -> "price_changes";
        case "sku_status_query" -> "listing_status";
        case "traffic_conversion_query" -> metric.equals("conversion_rate")?"conversion_delta":"traffic_delta";
        default -> "campaign_comparison";};}
    private static Map<String,Object> coverage(int i,String status,List<String> evidence,String reason){return Map.of("index",i,"status",status,"evidence",evidence,"reason",reason);}
    private static String call(String name,Map<String,Object> args){return json(Map.of("action","call_tool","tool",name,"arguments",args));}
    private static Map<String,String> ref(String path){return Map.of("$ref",path);}
    private static String id(JsonNode call){return call.path("callId").asText();}
    private static JsonNode result(JsonNode input,JsonNode call){return input.path("results").path(id(call));}
    private static JsonNode rows(JsonNode node){return node.isArray()?node:node.path("items").isArray()?node.get("items"):JSON.createArrayNode();}
    private static Object value(JsonNode input,String path){String[] p=path.split("\\.");JsonNode n=input.path("results");for(String part:p)n=n.path(part);return n.isNull()||n.isMissingNode()?null:JSON.convertValue(n,Object.class);}
    private static JsonNode block(String prompt,String tag)throws Exception{Matcher m=Pattern.compile("(?s)<"+tag+">(.*?)</"+tag+">").matcher(prompt);if(!m.find())throw new IllegalArgumentException("缺少输入");return JSON.readTree(m.group(1));}
    private static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
}
