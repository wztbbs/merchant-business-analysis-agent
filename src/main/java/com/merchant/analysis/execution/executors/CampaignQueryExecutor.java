package com.merchant.analysis.execution.executors;

import com.merchant.analysis.execution.AnalysisContext;
import com.merchant.analysis.execution.CapabilityExecutor;
import com.merchant.analysis.time.DateRange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** campaign_query：当前期 vs 基线期的活动有无及活动订单销售对比 */
@Component("campaignQueryExecutor")
public class CampaignQueryExecutor implements CapabilityExecutor {

    private final JdbcTemplate jdbc;

    public CampaignQueryExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "campaign_query";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AnalysisContext ctx) {
        List<Map<String, Object>> curCampaigns = campaignsIn(ctx.current());
        List<Map<String, Object>> baseCampaigns = campaignsIn(ctx.baseline());
        double curCampaignSales = campaignSales(ctx.current());
        double baseCampaignSales = campaignSales(ctx.baseline());

        String comment;
        if (!baseCampaigns.isEmpty() && curCampaigns.isEmpty()) {
            comment = String.format("前月有 %s 活动（%s）而上月无活动，活动空窗是环比下滑的次要因素。",
                    baseCampaigns.get(0).get("type"), baseCampaigns.get(0).get("rule"));
        } else if (curCampaigns.isEmpty() && baseCampaigns.isEmpty()) {
            comment = "两期均无活动，活动不是本次变化的影响因素。";
        } else {
            comment = "两期均有活动覆盖，活动因素基本对冲。";
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("current_campaigns", curCampaigns);
        comparison.put("baseline_campaigns", baseCampaigns);
        comparison.put("current_campaign_sales", round(curCampaignSales));
        comparison.put("baseline_campaign_sales", round(baseCampaignSales));
        comparison.put("comment", comment);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaign_comparison", comparison);
        return out;
    }

    private List<Map<String, Object>> campaignsIn(DateRange range) {
        List<Map<String, Object>> list = new ArrayList<>();
        jdbc.query("SELECT activity_id, type, start_time, end_time, rule FROM activity "
                        + "WHERE start_time < ? AND end_time >= ?",
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("activity_id", rs.getString(1));
                    m.put("type", rs.getString(2));
                    m.put("start_time", rs.getString(3));
                    m.put("end_time", rs.getString(4));
                    m.put("rule", rs.getString(5));
                    list.add(m);
                },
                range.endTs(), range.startTs());
        return list;
    }

    private double campaignSales(DateRange range) {
        Double v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM order_item WHERE ts >= ? AND ts < ? AND activity_id IS NOT NULL",
                Double.class, range.startTs(), range.endTs());
        return v == null ? 0 : v;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
