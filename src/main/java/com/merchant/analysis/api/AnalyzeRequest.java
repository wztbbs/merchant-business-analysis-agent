package com.merchant.analysis.api;

/**
 * 分析请求。
 * query：用户问题原文；
 * runId：可空——非空表示对已有运行记录重试（断点续跑），此时忽略 query，从 analysis_run 恢复；
 * merchantId / userId：可空——会话归属（merchantId 缺省为 default）；
 * sessionId：可空——指定后在该会话内追问（用户从会话列表选中历史会话）；缺省时命中追问自动接续最近活跃会话。
 */
public record AnalyzeRequest(String query, String runId, String merchantId, String userId, String sessionId) {
}
