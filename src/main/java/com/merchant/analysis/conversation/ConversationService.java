package com.merchant.analysis.conversation;

import com.merchant.analysis.understanding.ChainContext;
import com.merchant.analysis.understanding.UnderstandingResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话编排：为每次分析请求准备「候选会话 + 候选 run chain 链尾」，并在意图识别（含追问判定）后
 * 落定会话、写轮次、推进会话入口。
 *
 * 追问判定不在本类（由 QueryUnderstandingService 一次 LLM 调用完成）；本类只负责：
 * 取候选（显式 sessionId → 该会话的链；否则该用户最近的链）→ 按判定结果落定会话
 * （追问承接其他会话的 run 时挂到那个会话；非追问开新会话）。
 */
@Service
public class ConversationService {

    private static final String DEFAULT_MERCHANT = "default";
    private static final String DEFAULT_USER = "default";

    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    /** 意图识别前的候选上下文：候选会话（可空）+ 候选链尾（追问判定素材） */
    public record CandidateContext(String merchantId, String userId, SessionRecord candidateSession,
                                   List<ChainContext> chains) {
    }

    /**
     * 意图识别前取候选上下文；返回 null 表示无候选（首次访问 / 无 7 天窗口内的活跃会话），
     * 此时调用方按全新问题处理：chains 为空、直接开新会话。
     * 注意：窗口外的历史链不再隐式注入，用户需从会话列表显式选中会话（显式 sessionId 分支）。
     */
    public CandidateContext prepareContext(String merchantId, String userId, String sessionId) {
        String mid = normalize(merchantId, DEFAULT_MERCHANT);
        String uid = normalize(userId, DEFAULT_USER);

        // 显式指定会话（用户从会话列表选中历史会话，如两天前的）：候选取该会话的链
        if (sessionId != null && !sessionId.isBlank()) {
            SessionRecord session = repository.findById(sessionId);
            if (session == null) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
            return new CandidateContext(mid, uid, session, repository.findRecentChainTailsBySession(sessionId));
        }
        // 未指定：候选为该用户最近活跃会话；链按用户取（跨会话，承接"上次那个…"类追问）
        SessionRecord latest = repository.findLatestActive(mid, uid);
        if (latest == null) {
            return null;
        }
        return new CandidateContext(mid, uid, latest, repository.findRecentChainTailsByUser(mid, uid));
    }

    /** 无候选上下文（prepareContext 返回 null）时开新会话：对应 settle 中「非追问」的创建分支 */
    public SessionRecord createSession(String merchantId, String userId, String firstQuery) {
        return repository.create(normalize(merchantId, DEFAULT_MERCHANT), normalize(userId, DEFAULT_USER),
                firstQuery);
    }

    /**
     * 意图识别后落定会话：追问命中 → 挂到被承接 run 所属会话（缺省候选会话）；否则新建会话。
     */
    public SessionRecord settle(CandidateContext context, UnderstandingResult result, String firstQuery) {
        if (result.followUp() && result.refRunId() != null) {
            String refSessionId = repository.findSessionIdByRunId(result.refRunId());
            if (refSessionId != null) {
                return repository.findById(refSessionId);
            }
        }
        if (result.followUp() && context.candidateSession() != null) {
            return context.candidateSession();
        }
        return repository.create(context.merchantId(), context.userId(), firstQuery);
    }

    /** 产生了新 run 的轮次：写轮次（回答在 analysis_run，不重复存）+ 推进会话入口 */
    public String recordRunTurn(SessionRecord session, UnderstandingResult result, String userQuery,
                                String resolvedQuery, String runId) {
        String turnId = repository.addTurn(session.sessionId(), session.merchantId(), session.userId(),
                userQuery, resolvedQuery, result.followUp(), result.followUpType(),
                runId, result.refRunId(), null);
        repository.updateLastRun(session.sessionId(), runId);
        return turnId;
    }

    /** 不产生 run 的轮次（读历史型追问 / out_of_scope）：写轮次（answer 入库）+ 只刷活跃时间 */
    public String recordStandaloneTurn(SessionRecord session, UnderstandingResult result, String userQuery,
                                       String answer) {
        String turnId = repository.addTurn(session.sessionId(), session.merchantId(), session.userId(),
                userQuery, null, result.followUp(), result.followUpType(), null, result.refRunId(), answer);
        repository.touch(session.sessionId());
        return turnId;
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
