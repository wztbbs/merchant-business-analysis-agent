package com.merchant.analysis.playbook;

import java.util.List;
import java.util.Map;

/**
 * playbook YAML 中一个步骤的定义（未实例化）。
 * stepId 是语义化的定义 id（如 confirm_change_fact / confirm_delisting_event），
 * 编织时由 PlanWeavingContext 统一分配运行时 id（S1、S2…）；
 * 收尾步骤的 dependsOn 为 {scope: previous_steps}，编织时展开为全部前序步骤。
 */
public record StepTemplate(
        String stepId,
        String name,
        List<String> capabilities,
        Map<String, Object> params,   // 参数值可为字符串（含 {{xxx}} 占位符）或数字/布尔等原始类型
        Map<String, String> inputs,   // 输入名 → "步骤定义id.产出key" 或 "@产出key"
        List<String> dependsOn,
        List<String> outputs) {
}
