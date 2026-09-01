package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.DateRange;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * price_change_query：对比两期平均成交价，找出价格明显变化的 SKU。
 *
 * 作用域三级收敛（优先级从高到低），保证几千个 SKU 的商家产出也有界：
 * 1. params.sku 非空 → 只查该 SKU（因子影响评估场景，entity 直接给定）；
 * 2. ctx 中已有上游贡献拆解产出 top_contributors → 只查这些 SKU
 *    （隐式约定：不在 plan 的 inputs 中声明，以保证因子模块跨场景可移植）；
 * 3. 否则全店扫描，命中的变化按销售额加权取前 TOP_N。
 */
@Component("priceChangeQueryExecutor")
public class PriceChangeQueryExecutor implements CapabilityExecutor {

    /** 均价变化超过 1% 视为「明显变化」 */
    private static final double CHANGE_THRESHOLD = 0.01;
    /** 全店扫描时的产出上限（按销售额加权排序） */
    private static final int TOP_N = 10;

    /** skuIds 为 null 表示全店扫描 */
    private record Scope(Set<String> skuIds, String description) {
    }

    private final JdbcTemplate jdbc;

    public PriceChangeQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "price_change_query";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        Scope scope = resolveScope(params, ctx);
        // sku_id → [avg_price, total_sales]
        Map<String, double[]> cur = skuStats(ctx.current(), scope.skuIds());
        Map<String, double[]> base = skuStats(ctx.baseline(), scope.skuIds());

        Map<String, Double> salesWeight = new HashMap<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map.Entry<String, double[]> e : cur.entrySet()) {
            double[] baseStat = base.get(e.getKey());
            if (baseStat == null || baseStat[0] == 0) {
                continue;
            }
            double changeRatio = (e.getValue()[0] - baseStat[0]) / baseStat[0];
            if (Math.abs(changeRatio) >= CHANGE_THRESHOLD) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sku_id", e.getKey());
                item.put("baseline_avg_price", round(baseStat[0]));
                item.put("current_avg_price", round(e.getValue()[0]));
                item.put("change_ratio", round(changeRatio));
                changes.add(item);
                salesWeight.put(e.getKey(), e.getValue()[1] + baseStat[1]);
            }
        }

        if (scope.skuIds() == null) {
            // 全店扫描：按销售额加权取前 TOP_N，避免产出无界
            changes.sort(Comparator.comparingDouble(
                    (Map<String, Object> m) -> -salesWeight.get(m.get("sku_id").toString())));
            changes = changes.stream().limit(TOP_N).toList();
        } else {
            changes.sort(Comparator.comparingDouble(
                    (Map<String, Object> m) -> -Math.abs(((Number) m.get("change_ratio")).doubleValue())));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("price_changes", changes);
        out.put("scope", scope.description());
        out.put("comment", changes.isEmpty()
                ? "两期均价无明显变化（" + scope.description() + "），价格因素可排除。"
                : "发现 " + changes.size() + " 个 SKU 均价明显变化（" + scope.description() + "）。");
        return out;
    }

    private Scope resolveScope(Map<String, Object> params, AnalysisContext ctx) {
        // 规则 1：显式 sku 参数
        Object sku = params.get("sku");
        if (sku instanceof String skuName && !skuName.isBlank()) {
            String skuId = skuIdOf(skuName);
            return new Scope(skuId == null ? Set.of() : Set.of(skuId), "指定SKU「" + skuName + "」");
        }
        // 规则 2：隐式复用上游贡献拆解的 top_contributors（如 S2 已先执行）
        for (Map<String, Object> stepResult : ctx.stepResults().values()) {
            Object contributors = stepResult.get("top_contributors");
            if (contributors instanceof List<?> list && !list.isEmpty()) {
                Set<String> ids = new LinkedHashSet<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("sku_id") != null) {
                        ids.add(m.get("sku_id").toString());
                    }
                }
                if (!ids.isEmpty()) {
                    return new Scope(ids, "上游贡献拆解 Top SKU（" + ids.size() + " 个）");
                }
            }
        }
        // 规则 3：全店扫描兜底
        return new Scope(null, "全店扫描，按销售额加权取前 " + TOP_N);
    }

    /** sku_id → [avg_price, total_sales]；skuIds 为 null 时全店扫描 */
    private Map<String, double[]> skuStats(DateRange range, Set<String> skuIds) {
        if (skuIds != null && skuIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT sku_id, AVG(price), SUM(amount) FROM order_item WHERE ts >= ? AND ts < ?");
        List<Object> args = new ArrayList<>(List.of(range.startTs(), range.endTs()));
        if (skuIds != null) {
            sql.append(" AND sku_id IN (").append("?,".repeat(skuIds.size()));
            sql.setLength(sql.length() - 1);
            sql.append(")");
            args.addAll(skuIds);
        }
        sql.append(" GROUP BY sku_id");

        Map<String, double[]> result = new HashMap<>();
        jdbc.query(sql.toString(), rs -> {
            result.put(rs.getString(1), new double[]{rs.getDouble(2), rs.getDouble(3)});
        }, args.toArray());
        return result;
    }

    private String skuIdOf(String skuName) {
        try {
            return jdbc.queryForObject("SELECT sku_id FROM sku WHERE name = ?", String.class, skuName);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
