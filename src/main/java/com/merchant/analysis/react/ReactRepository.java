package com.merchant.analysis.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class ReactRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    public ReactRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void save(String runId, ReactState state) {
        jdbc.update("INSERT INTO react_run(run_id,state_json,updated_at) VALUES(?,?,?) "
                + "ON CONFLICT(run_id) DO UPDATE SET state_json=excluded.state_json,updated_at=excluded.updated_at",
                runId, json(state), Instant.now().toString());
    }
    public ReactState load(String runId) {
        return jdbc.query("SELECT state_json FROM react_run WHERE run_id=?", rs -> {
            if (!rs.next()) return null;
            try { return JSON.readValue(rs.getString(1), ReactState.class); }
            catch (Exception e) { throw new IllegalStateException("ReAct 状态不可解析", e); }
        }, runId);
    }
    public int lastSequence(String runId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0) FROM react_event WHERE run_id=?", Integer.class, runId);
        return value == null ? 0 : value;
    }
    public void event(String runId, int sequence, String kind, String name, Object input, Object output,
                      String status, long duration, String error) {
        jdbc.update("INSERT INTO react_event(run_id,seq,kind,name,input_json,output_json,status,duration_ms,error,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?)", runId, sequence, kind, name, json(input), json(output), status, duration, error, Instant.now().toString());
    }
    public List<Map<String, Object>> events(String runId) {
        return jdbc.queryForList("SELECT seq,kind,name,input_json,output_json,status,duration_ms,error,created_at "
                + "FROM react_event WHERE run_id=? ORDER BY seq", runId);
    }
    public void step(String runId, String callId, String name) {
        jdbc.update("INSERT INTO step_run(run_id,step_id,step_name,status,started_at) VALUES(?,?,?,'RUNNING',?) "
                + "ON CONFLICT(run_id,step_id) DO UPDATE SET status='RUNNING',started_at=excluded.started_at,note=NULL,finished_at=NULL",
                runId, callId, name, Instant.now().toString());
    }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("ReAct 轨迹序列化失败", e); }
    }
}
