package com.merchant.analysis.conversation;

import com.merchant.analysis.understanding.ChainContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话持久化：conversation_session + conversation_turn。
 * 会话按 merchant_id + user_id 隔离：同一店铺多个运营的会话互不可见；
 * 会话找回策略：默认接续该用户最近 7 天活跃会话；不做文本匹配式检索。
 * run chain 通过 conversation_turn.run_id 关联 analysis_run 推导（root_run_id 相同的 run 为一条话题线）。
 */
@Repository
public class ConversationRepository {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 默认接续窗口：超过 7 天未活跃的会话不自动接续（仍可在会话列表中手动选中） */
    private static final int ACTIVE_WINDOW_DAYS = 7;
    /** 注入 understanding prompt 的候选链尾数量上限 */
    private static final int CHAIN_CANDIDATE_LIMIT = 2;

    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建会话：title / topic_summary 取首问原文（截断），不产生 LLM 调用 */
    public SessionRecord create(String merchantId, String userId, String firstQuery) {
        String sessionId = UUID.randomUUID().toString();
        String title = firstQuery.length() <= 20 ? firstQuery : firstQuery.substring(0, 20) + "…";
        jdbc.update("INSERT INTO conversation_session(session_id, merchant_id, user_id, title, status, "
                        + "topic_summary, last_active_at, created_at) VALUES(?,?,?,?,'active',?,?,?)",
                sessionId, merchantId, userId, title, firstQuery, now(), now());
        return findById(sessionId);
    }

    public SessionRecord findById(String sessionId) {
        return jdbc.query("SELECT session_id, merchant_id, user_id, title, status, last_run_id, "
                        + "topic_summary, last_active_at, created_at FROM conversation_session WHERE session_id=?",
                rs -> rs.next() ? map(rs) : null, sessionId);
    }

    /** 该用户最近活跃的会话（7 天窗口内）；没有返回 null */
    public SessionRecord findLatestActive(String merchantId, String userId) {
        String cutoff = LocalDateTime.now().minusDays(ACTIVE_WINDOW_DAYS).format(TS);
        List<SessionRecord> found = jdbc.query("SELECT session_id, merchant_id, user_id, title, status, "
                        + "last_run_id, topic_summary, last_active_at, created_at FROM conversation_session "
                        + "WHERE merchant_id=? AND user_id=? AND status='active' AND last_active_at>=? "
                        + "ORDER BY last_active_at DESC LIMIT 1",
                (rs, i) -> map(rs), merchantId, userId, cutoff);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 会话列表（侧边栏）：该用户的会话，按最近活跃倒序 */
    public List<SessionRecord> listByUser(String merchantId, String userId, int limit) {
        return jdbc.query("SELECT session_id, merchant_id, user_id, title, status, last_run_id, "
                        + "topic_summary, last_active_at, created_at FROM conversation_session "
                        + "WHERE merchant_id=? AND user_id=? ORDER BY last_active_at DESC LIMIT ?",
                (rs, i) -> map(rs), merchantId, userId, limit);
    }

    /**
     * 取最近 N 条 run chain 的链尾（追问判定的候选上下文）。
     * chain = root_run_id 相同的一组 run；链尾 = 组内最新 run。scope 为 session 或该用户全部会话。
     */
    public List<ChainContext> findRecentChainTailsBySession(String sessionId) {
        return pickChainTails(
                "SELECT r.run_id, COALESCE(r.root_run_id, r.run_id) AS root, r.query, r.understanding_json "
                        + "FROM analysis_run r JOIN conversation_turn t ON t.run_id = r.run_id "
                        + "WHERE t.session_id = ? ORDER BY r.created_at DESC",
                sessionId);
    }

    public List<ChainContext> findRecentChainTailsByUser(String merchantId, String userId) {
        return pickChainTails(
                "SELECT r.run_id, COALESCE(r.root_run_id, r.run_id) AS root, r.query, r.understanding_json "
                        + "FROM analysis_run r JOIN conversation_turn t ON t.run_id = r.run_id "
                        + "WHERE t.merchant_id = ? AND t.user_id = ? ORDER BY r.created_at DESC",
                merchantId, userId);
    }

    /** 按 run 反查所属会话（追问承接了其他会话的 run 时，把本轮挂到那个会话） */
    public String findSessionIdByRunId(String runId) {
        List<String> found = jdbc.query("SELECT session_id FROM conversation_turn WHERE run_id=? LIMIT 1",
                (rs, i) -> rs.getString(1), runId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 产生新 run 的轮次后更新会话入口信息 */
    public void updateLastRun(String sessionId, String runId) {
        jdbc.update("UPDATE conversation_session SET last_run_id=?, last_active_at=? WHERE session_id=?",
                runId, now(), sessionId);
    }

    /** 不产生 run 的轮次（读历史追问 / out_of_scope）只刷新活跃时间 */
    public void touch(String sessionId) {
        jdbc.update("UPDATE conversation_session SET last_active_at=? WHERE session_id=?", now(), sessionId);
    }

    /** 记录一轮问答；返回 turn_id。runId 为本轮产出的 run；refRunId 为本轮引用/承接的 run */
    public String addTurn(String sessionId, String merchantId, String userId, String userQuery,
                          String resolvedQuery, boolean isFollowUp, String followUpType,
                          String runId, String refRunId, String answer) {
        String turnId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation_turn(turn_id, session_id, merchant_id, user_id, user_query, "
                        + "resolved_query, is_follow_up, follow_up_type, run_id, ref_run_id, answer, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                turnId, sessionId, merchantId, userId, userQuery, resolvedQuery, isFollowUp ? 1 : 0,
                followUpType, runId, refRunId, answer, now());
        return turnId;
    }

    /** 会话历史：run 型轮次的回答 join analysis_run.conclusion；无 run 轮次取 answer */
    public List<TurnRecord> listTurns(String sessionId) {
        return jdbc.query("SELECT t.turn_id, t.user_query, t.resolved_query, t.is_follow_up, t.follow_up_type, "
                        + "t.run_id, t.ref_run_id, COALESCE(r.conclusion, t.answer), t.created_at "
                        + "FROM conversation_turn t LEFT JOIN analysis_run r ON t.run_id = r.run_id "
                        + "WHERE t.session_id=? ORDER BY t.created_at",
                (rs, i) -> new TurnRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4) == 1, rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)),
                sessionId);
    }

    /** 会话历史的一轮：answer 为系统回答（run 结论或读历史回答） */
    public record TurnRecord(String turnId, String userQuery, String resolvedQuery, boolean isFollowUp,
                             String followUpType, String runId, String refRunId, String answer,
                             String createdAt) {
    }

    /** 按时间倒序的 run 流 → 每链取最新一条为链尾，取前 N 条链 */
    private List<ChainContext> pickChainTails(String sql, Object... args) {
        Map<String, ChainContext> tailByRoot = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String root = rs.getString(2);
            tailByRoot.putIfAbsent(root, new ChainContext(root, rs.getString(1),
                    rs.getString(3), rs.getString(4)));
        }, args);
        return new ArrayList<>(tailByRoot.values()).stream().limit(CHAIN_CANDIDATE_LIMIT).toList();
    }

    private static SessionRecord map(ResultSet rs) throws SQLException {
        return new SessionRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
