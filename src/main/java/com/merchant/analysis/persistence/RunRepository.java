package com.merchant.analysis.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.plan.Plan;
import com.merchant.analysis.plan.PlanStep;
import com.merchant.analysis.understanding.QueryUnderstanding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 运行状态持久化：analysis_run（一次分析一行）+ step_run（一步一行，状态机）。
 * 每步执行完即落库；断点续跑时从 step_run 恢复已成功步骤的产出，只重跑未完成步骤。
 */
@Repository
public class RunRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** analysis_run 行记录 */
    public record RunRecord(String runId, String query, String scene, String planJson,
                            String understandingJson, String status, boolean hypothesisBroken,
                            String conclusion) {
    }

    private final JdbcTemplate jdbc;

    public RunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建运行记录（RUNNING），并将 plan 全部步骤以 PENDING 预写入 step_run；
     *  rootRunId：run chain 归属——首问为 null（自己是链根），追问重跑的新 run 指向链根 */
    public String createRun(String query, Plan plan, QueryUnderstanding understanding, String rootRunId) {
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO analysis_run(run_id, root_run_id, query, scene, plan_json, understanding_json, "
                        + "status, created_at) VALUES(?,?,?,?,?,?,'RUNNING',?)",
                runId, rootRunId, query, plan.scene(), toJson(plan), toJson(understanding), now());
        for (PlanStep s : plan.steps()) {
            jdbc.update("INSERT INTO step_run(run_id, step_id, step_name, factor, status) VALUES(?,?,?,?,'PENDING')",
                    runId, s.stepId(), s.name(), s.factor());
        }
        return runId;
    }

    public RunRecord findRun(String runId) {
        return jdbc.query("SELECT run_id, query, scene, plan_json, understanding_json, status, "
                        + "hypothesis_broken, conclusion FROM analysis_run WHERE run_id=?",
                rs -> rs.next() ? new RunRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getInt(7) == 1, rs.getString(8)) : null,
                runId);
    }

    /** 重试：运行记录重置为 RUNNING */
    public void markRunRunning(String runId) {
        jdbc.update("UPDATE analysis_run SET status='RUNNING', error=NULL, finished_at=NULL WHERE run_id=?", runId);
    }

    public void finishRun(String runId, String status, boolean hypothesisBroken, String conclusion, String error) {
        jdbc.update("UPDATE analysis_run SET status=?, hypothesis_broken=?, conclusion=?, error=?, finished_at=? "
                        + "WHERE run_id=?",
                status, hypothesisBroken ? 1 : 0, conclusion, error, now(), runId);
    }

    /** 断点续跑 / 结果回读：加载所有 SUCCESS 步骤的产出（SKIPPED 步无产出，天然不在其中） */
    public Map<String, Map<String, Object>> loadSuccessfulStepOutputs(String runId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        jdbc.query("SELECT step_id, outputs_json FROM step_run WHERE run_id=? AND status='SUCCESS' ORDER BY id",
                rs -> {
                    String json = rs.getString(2);
                    result.put(rs.getString(1), json == null ? Map.of() : fromJson(json));
                }, runId);
        return result;
    }

    public void markStepRunning(String runId, String stepId) {
        jdbc.update("UPDATE step_run SET status='RUNNING', started_at=? WHERE run_id=? AND step_id=?",
                now(), runId, stepId);
    }

    public void markStepSuccess(String runId, String stepId, Map<String, Object> outputs) {
        jdbc.update("UPDATE step_run SET status='SUCCESS', outputs_json=?, note=NULL, finished_at=? "
                        + "WHERE run_id=? AND step_id=?",
                toJson(outputs), now(), runId, stepId);
    }

    public void markStepFailed(String runId, String stepId, String error) {
        jdbc.update("UPDATE step_run SET status='FAILED', note=?, finished_at=? WHERE run_id=? AND step_id=?",
                error, now(), runId, stepId);
    }

    public void markStepSkipped(String runId, String stepId, String reason) {
        jdbc.update("UPDATE step_run SET status='SKIPPED', note=?, finished_at=? WHERE run_id=? AND step_id=?",
                reason, now(), runId, stepId);
    }

    public static Plan planFromJson(String json) {
        try {
            return MAPPER.readValue(json, Plan.class);
        } catch (Exception e) {
            throw new IllegalStateException("plan_json 反序列化失败", e);
        }
    }

    public static QueryUnderstanding understandingFromJson(String json) {
        try {
            return MAPPER.readValue(json, QueryUnderstanding.class);
        } catch (Exception e) {
            throw new IllegalStateException("understanding_json 反序列化失败", e);
        }
    }

    private static Map<String, Object> fromJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("step outputs_json 反序列化失败", e);
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("运行状态序列化失败", e);
        }
    }
}
