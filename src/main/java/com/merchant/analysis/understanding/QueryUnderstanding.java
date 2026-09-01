package com.merchant.analysis.understanding;

import java.util.List;

/**
 * 意图识别结果（LLM 输出 JSON 的直接映射，字段名保持 snake_case 与 prompt 约定一致）。
 */
public record QueryUnderstanding(
        String business_intent,
        String metric,
        String time_range,           // 逻辑时间：last_month / this_month
        String comparison_baseline,  // 对比基线：previous_month
        Entity entity,
        String candidate_factor,     // 用户假设因子，可空
        String suggest_action,       // 需求采集切面：分析动作（仅 out_of_scope 时非空）
        String suggest_object,       // 需求采集切面：分析对象（仅 out_of_scope 时非空）
        String suggest_entity,       // 需求采集切面：涉及实体（仅 out_of_scope 时非空）
        Scene scene) {

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
