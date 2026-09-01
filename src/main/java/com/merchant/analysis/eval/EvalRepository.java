package com.merchant.analysis.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** eval_case / eval_run / eval_case_result 三张表的读写 */
@Repository
public class EvalRepository {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** eval_case 行记录；expected_* 为 NULL 表示该字段不参与比较 */
    public record EvalCase(long id, String query, String expectedScene, String expectedMetric,
                           String expectedTimeRange, String expectedEntitySku, String expectedCandidateFactor,
                           String split) {
    }

    /** 待 grounding 校验的生产 trace：run_id + 结论 + 假设证伪标记 + 原始 query（实体检查用）
     *  + metric / sku（符号一致性与主因点名的口径判断用，从 understanding_json 解析，可能为 null） */
    public record GroundingTarget(String runId, String conclusion, boolean hypothesisBroken, String query,
                                  String metric, String sku) {
    }

    private final JdbcTemplate jdbc;

    public EvalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EvalCase> loadCases(String split) {
        String sql = "SELECT id, query, expected_scene, expected_metric, expected_time_range, "
                + "expected_entity_sku, expected_candidate_factor, split FROM eval_case"
                + (split == null ? "" : " WHERE split=?");
        return split == null
                ? jdbc.query(sql, (rs, i) -> toCase(rs))
                : jdbc.query(sql, (rs, i) -> toCase(rs), split);
    }

    private static EvalCase toCase(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EvalCase(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
    }

    public void createEvalRun(String evalRunId, String evalType, String model, String promptVersion) {
        jdbc.update("INSERT INTO eval_run(eval_run_id, eval_type, model, prompt_version, status, created_at) "
                + "VALUES(?,?,?,?,'RUNNING',?)", evalRunId, evalType, model, promptVersion, now());
    }

    public void finishEvalRun(String evalRunId, String status, int totalCases, int passedCases, String metricsJson) {
        jdbc.update("UPDATE eval_run SET status=?, total_cases=?, passed_cases=?, metrics_json=?, finished_at=? "
                + "WHERE eval_run_id=?", status, totalCases, passedCases, metricsJson, now(), evalRunId);
    }

    /** pass_* 列：1/0 参与比较，null 未参与（AVG 统计时自动忽略）；passed 为本 case 整体通过位 */
    public void insertCaseResult(String evalRunId, Long caseId, String analysisRunId, String actualJson,
                                 Integer passScene, Integer passMetric, Integer passTimeRange,
                                 Integer passEntity, Integer passFactor, Integer groundingFailCount,
                                 boolean passed, String detailJson) {
        jdbc.update("INSERT INTO eval_case_result(eval_run_id, case_id, analysis_run_id, actual_json, "
                        + "pass_scene, pass_metric, pass_time_range, pass_entity, pass_factor, "
                        + "grounding_fail_count, passed, detail_json, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evalRunId, caseId, analysisRunId, actualJson, passScene, passMetric, passTimeRange,
                passEntity, passFactor, groundingFailCount, passed ? 1 : 0, detailJson, now());
    }

    /** 最近的成功分析运行（grounding 评测对象） */
    public List<GroundingTarget> loadGroundingTargets(int limit) {
        return jdbc.query("SELECT run_id, conclusion, hypothesis_broken, query, understanding_json "
                        + "FROM analysis_run "
                        + "WHERE status='SUCCESS' AND conclusion IS NOT NULL ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> {
                    String understandingJson = rs.getString(5);
                    return new GroundingTarget(rs.getString(1), rs.getString(2), rs.getInt(3) == 1,
                            rs.getString(4), extractMetric(understandingJson), extractSku(understandingJson));
                }, limit);
    }

    /** 从 understanding_json 中解析 metric / entity.sku；解析失败按 null 处理（校验规则自行降级） */
    private static String extractMetric(String understandingJson) {
        try {
            JsonNode node = MAPPER.readTree(understandingJson).path("metric");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractSku(String understandingJson) {
        try {
            JsonNode node = MAPPER.readTree(understandingJson).path("entity").path("sku");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** label upsert：query 已存在则更新标注字段，否则插入。返回 "inserted" / "updated" */
    public String upsertCase(String query, String expectedScene, String expectedMetric,
                             String expectedTimeRange, String expectedEntitySku,
                             String expectedCandidateFactor, String split, String source) {
        Long id = jdbc.query("SELECT id FROM eval_case WHERE query=?",
                rs -> rs.next() ? rs.getLong(1) : null, query);
        if (id == null) {
            jdbc.update("INSERT INTO eval_case(query, expected_scene, expected_metric, expected_time_range, "
                            + "expected_entity_sku, expected_candidate_factor, split, source, created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)",
                    query, expectedScene, expectedMetric, expectedTimeRange, expectedEntitySku,
                    expectedCandidateFactor, split == null ? "dev" : split,
                    source == null ? "manual" : source, now());
            return "inserted";
        }
        jdbc.update("UPDATE eval_case SET expected_scene=?, expected_metric=?, expected_time_range=?, "
                        + "expected_entity_sku=?, expected_candidate_factor=?, split=?, source=? WHERE id=?",
                expectedScene, expectedMetric, expectedTimeRange, expectedEntitySku,
                expectedCandidateFactor, split == null ? "dev" : split,
                source == null ? "manual" : source, id);
        return "updated";
    }

    public Map<String, Object> findEvalRun(String evalRunId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM eval_run WHERE eval_run_id=?", evalRunId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listCaseResults(String evalRunId) {
        return jdbc.queryForList("SELECT * FROM eval_case_result WHERE eval_run_id=? ORDER BY id", evalRunId);
    }

    /** 跨批次聚合统计：按 eval_type / model / prompt_version 分组（pass 列打平后统计就是 AVG） */
    public List<Map<String, Object>> stats(String evalType) {
        String sql = "SELECT r.eval_type, r.model, r.prompt_version, "
                + "COUNT(DISTINCT r.eval_run_id) AS runs, COUNT(c.id) AS cases, SUM(c.passed) AS passed, "
                + "AVG(c.passed) AS case_pass_rate, "
                + "AVG(c.pass_scene) AS scene_acc, AVG(c.pass_metric) AS metric_acc, "
                + "AVG(c.pass_time_range) AS time_range_acc, AVG(c.pass_entity) AS entity_acc, "
                + "AVG(c.pass_factor) AS factor_acc "
                + "FROM eval_run r JOIN eval_case_result c ON c.eval_run_id = r.eval_run_id "
                + (evalType == null ? "" : "WHERE r.eval_type = ? ")
                + "GROUP BY r.eval_type, r.model, r.prompt_version";
        return evalType == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, evalType);
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
