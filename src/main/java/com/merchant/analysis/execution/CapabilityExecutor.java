package com.merchant.analysis.execution;

import java.util.Map;

/**
 * 能力执行器：一个 capability 对应一个实现（Spring bean 名与 capabilities.yaml 的 executor 字段一致）。
 * params 为 plan 步骤参数 + 已解析的 inputs 引用值；返回 outputKey → 值。
 */
public interface CapabilityExecutor {

    String name();

    Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx);
}
