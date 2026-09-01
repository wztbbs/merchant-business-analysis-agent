package com.merchant.analysis.plan;

import java.util.List;
import java.util.Map;

/**
 * 实例化后的执行步骤。
 * factor 非空表示该步来自某个因子模块（用于执行期的动态调整，如假设证伪后跳过后续步骤）。
 */
public record PlanStep(
        String stepId,
        String name,
        String factor,
        List<String> capabilities,
        Map<String, Object> params,
        Map<String, String> inputs,   // 输入名 → "步骤id.输出key"（已实例化）
        List<String> dependsOn,
        List<String> outputs) {
}
