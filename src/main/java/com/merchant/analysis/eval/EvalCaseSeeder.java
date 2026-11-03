package com.merchant.analysis.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * eval_case 标注集种子：表为空时插入一批起步 case（dev/test 各半，含 out_of_scope 对抗样本）。
 * 上线后应持续从 bad_query_case 和线上日志回流标注，此处仅保证开箱可跑。
 */
@Component
public class EvalCaseSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCaseSeeder.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** query / scene / metric / time_range / entity_sku / candidate_factor / split */
    private static final List<String[]> SEEDS = List.of(
            new String[]{"为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？", "metric_change_diagnosis",
                    "sales_amount", "last_month", "无糖可乐", "delisting", "dev"},
            new String[]{"上个月销售额是多少？", "metric_query_report",
                    "sales_amount", "last_month", null, null, "dev"},
            new String[]{"我把无糖可乐下架了影响大吗？", "factor_impact_evaluation",
                    "sales_amount", "last_month", "无糖可乐", "delisting", "dev"},
            new String[]{"今天天气怎么样", "out_of_scope",
                    null, null, null, null, "dev"},
            new String[]{"帮我查一下这个月的订单量", "metric_query_report",
                    "order_count", "this_month", null, null, "dev"},
            new String[]{"为什么这个月客单价降了？", "metric_change_diagnosis",
                    "aov", "this_month", null, null, "test"},
            new String[]{"最近缺货对销售额影响大吗？", "factor_impact_evaluation",
                    "sales_amount", "last_month", null, "stockout", "test"},
            new String[]{"帮我预测一下下个月库存够不够用", "other",
                    null, null, null, null, "test"},
            new String[]{"上个月销售额是多少", "metric_query_report",
                    "sales_amount", "last_month", null, null, "test"}
    );

    private final JdbcTemplate jdbc;

    public EvalCaseSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM eval_case", Long.class);
        if (count != null && count > 0) {
            log.info("eval_case 已有 {} 条，跳过种子插入", count);
            return;
        }
        String now = LocalDateTime.now().format(TS);
        for (String[] s : SEEDS) {
            jdbc.update("INSERT INTO eval_case(query, expected_scene, expected_metric, expected_time_range, "
                            + "expected_entity_sku, expected_candidate_factor, split, source, created_at) "
                            + "VALUES(?,?,?,?,?,?,?, 'manual', ?)",
                    s[0], s[1], s[2], s[3], s[4], s[5], s[6], now);
        }
        log.info("eval_case 种子插入 {} 条（dev/test 各半，含 out_of_scope 对抗样本）", SEEDS.size());
    }
}
