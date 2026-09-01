package com.merchant.analysis.conversation;

/**
 * 会话记录：conversation_session 行。会话只承载归属与入口信息；
 * 追问上下文不在会话上快照，由 run chain（analysis_run.root_run_id）+ 链尾 run 的 understanding 承担。
 */
public record SessionRecord(
        String sessionId,
        String merchantId,
        String userId,
        String title,
        String status,
        String lastRunId,
        String topicSummary,
        String lastActiveAt,
        String createdAt) {
}
