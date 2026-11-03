package com.merchant.analysis.understanding;

/**
 * run chain 的链尾视图：一次话题线（chain）里最近一次分析的最小补全素材。
 * 追问判定时把最近若干条链尾注入 understanding prompt，LLM 基于它判断追问归属并补齐缺失字段。
 *
 * @param rootRunId         链根 run（首问）；链根自身 rootRunId == runId
 * @param runId             链尾 run（该话题线最近一次分析）
 * @param query             链尾 run 的问题原文
 * @param understandingJson 链尾 run 的意图识别结果（JSON 原文，直接注入 prompt）
 */
public record ChainContext(String rootRunId, String runId, String query, String understandingJson,
                           java.util.Map<String, java.util.Map<String, Object>> stepResults, String runStatus) {
    public ChainContext(String rootRunId, String runId, String query, String understandingJson,
                        java.util.Map<String, java.util.Map<String, Object>> stepResults) {
        this(rootRunId, runId, query, understandingJson, stepResults, "SUCCESS");
    }
    public ChainContext(String rootRunId, String runId, String query, String understandingJson) {
        this(rootRunId, runId, query, understandingJson, java.util.Map.of(), "SUCCESS");
    }
    public ChainContext {
        stepResults = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(stepResults));
    }
}
