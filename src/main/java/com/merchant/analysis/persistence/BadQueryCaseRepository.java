package com.merchant.analysis.persistence;

import com.merchant.analysis.understanding.QueryUnderstanding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 未支持需求采集：out_of_scope 的 query 落库 bad_query_case。
 * 聚合统计直接按 (suggest_action, suggest_object) GROUP BY 精确匹配——
 * 结构化切面就是为了避免自由命名发散导致无法统计。
 */
@Repository
public class BadQueryCaseRepository {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public BadQueryCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String query, QueryUnderstanding u) {
        jdbc.update("INSERT INTO bad_query_case(query, business_intent, suggest_action, suggest_object, "
                        + "suggest_entity, created_at) VALUES(?,?,?,?,?,?)",
                query, u.business_intent(), u.suggest_action(), u.suggest_object(),
                u.suggest_entity(), LocalDateTime.now().format(TS));
    }
}
