package com.merchant.analysis.understanding;

import com.merchant.analysis.time.TimeSpec;
import com.merchant.analysis.time.ResolvedTime;
import java.util.List;

/**
 * 意图识别结果（LLM 输出 JSON 的直接映射，字段名保持 snake_case 与 prompt 约定一致）。
 */
public record QueryUnderstanding(
        String business_intent,
        String metric,
        TimeSpec time_range,
        TimeSpec comparison_baseline, // null 表示按观察期推导上一可比周期
        Entity entity,
        String candidate_factor,     // 用户假设因子，可空
        String suggest_action,       // 需求采集切面：分析动作（仅 out_of_scope 时非空）
        String suggest_object,       // 需求采集切面：分析对象（仅 out_of_scope 时非空）
        String suggest_entity,       // 需求采集切面：涉及实体（仅 out_of_scope 时非空）
        Scene scene,
        ResolvedTime resolved_time,
        QueryTask task,
        RoutingDecision routing) {

    public QueryUnderstanding(String business_intent, String metric, TimeSpec time_range,
                              TimeSpec comparison_baseline, Entity entity, String candidate_factor,
                              String suggest_action, String suggest_object, String suggest_entity,
                              Scene scene, ResolvedTime resolved_time) {
        this(business_intent, metric, time_range, comparison_baseline, entity, candidate_factor,
                suggest_action, suggest_object, suggest_entity, scene, resolved_time, null, null);
    }

    public QueryUnderstanding(String business_intent, String metric, TimeSpec time_range,
                              TimeSpec comparison_baseline, Entity entity, String candidate_factor,
                              String suggest_action, String suggest_object, String suggest_entity, Scene scene) {
        this(business_intent, metric, time_range, comparison_baseline, entity, candidate_factor,
                suggest_action, suggest_object, suggest_entity, scene, null);
    }

    public QueryUnderstanding withResolvedTime(ResolvedTime time) {
        return new QueryUnderstanding(business_intent, metric, time_range, comparison_baseline, entity,
                candidate_factor, suggest_action, suggest_object, suggest_entity, scene, time, task, routing);
    }

    public QueryUnderstanding withInterpretation(QueryTask task, RoutingDecision routing) {
        return new QueryUnderstanding(business_intent, metric, time_range, comparison_baseline, entity,
                candidate_factor, suggest_action, suggest_object, suggest_entity, scene, resolved_time, task, routing);
    }

    public QueryUnderstanding withScene(String primaryScene) {
        return new QueryUnderstanding(business_intent, metric, time_range, comparison_baseline, entity,
                candidate_factor, suggest_action, suggest_object, suggest_entity,
                new Scene(primaryScene, scene == null ? List.of() : scene.factor_modules()), resolved_time, task, routing);
    }

    public record Entity(String sku) {
    }

    public record Scene(String primary_scene, List<String> factor_modules) {
    }

    public String skuOrNull() {
        return entity == null ? null : entity.sku();
    }

    public String primaryScene() {
        return scene == null ? null : scene.primary_scene();
    }
}
