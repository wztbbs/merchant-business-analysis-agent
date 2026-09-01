package com.merchant.analysis.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子数据生成器：仅当 order_item 为空时插入（幂等）。
 * 用 Java 而非 SQL 造数，便于控制分布。全部日期基于 LocalDate.now() 相对计算，
 * 保证任何时候启动「上月/前月」都有数据。
 *
 * 数据故事（支撑 demo 问题「为什么上个月销售额下滑了，跟下架的无糖可乐有关吗？」）：
 * - 30 个 SKU，其中「无糖可乐」(sku01, 饮料) 是热销品：前月每天约 300 件；
 * - 无糖可乐在上月 15 号下架 → 下半月及当月销售归零，是下滑贡献最大的单一 SKU；
 * - 其余 SKU 上月销量整体约为前月的 82%；
 * - 前月 10~20 号有一档「满50减10」满减活动，上月无活动；
 * - 上月曝光/进店 UV 约为前月的 85%（次要因素）；
 * - sku15 在上月 5~9 号有一次缺货事件（供缺货因子排查）；
 * - sku08（巧克力）自上月 1 号起涨价 15%，并延续到当月（价格因子正例）；
 * - 当月 1 号到生成当天也有数据（销量延续上月水平），保证 this_month 查询非空。
 *   注意：种子只在 order_item 为空时生成一次，当月起数据不会逐日增长；
 *   需要当月最新数据时删库重启重造。
 */
@Component
public class SeedDataGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataGenerator.class);

    /** 无糖可乐：热销 + 上月中旬下架 */
    private static final String COLA_SKU = "sku01";
    private static final double COLA_PRICE = 3.5;
    private static final int COLA_DAILY_QTY = 300;
    private static final int COLA_DELIST_DAY = 15;

    /** 普通 SKU 上月销量环比系数 */
    private static final double LAST_MONTH_FACTOR = 0.80;
    /** 上月流量环比系数 */
    private static final double TRAFFIC_FACTOR = 0.85;

    /** 涨价正例：sku08 自上月 1 号起涨价 15% */
    private static final String PRICE_UP_SKU = "sku08";
    private static final double PRICE_UP_FACTOR = 1.15;

    private final JdbcTemplate jdbc;

    public SeedDataGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class);
        if (count != null && count > 0) {
            log.info("种子数据已存在（order_item {} 行），跳过生成", count);
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDate firstOfThisMonth = today.withDayOfMonth(1);
        LocalDate lastStart = firstOfThisMonth.minusMonths(1);   // 上月
        LocalDate prevStart = firstOfThisMonth.minusMonths(2);   // 前月（基线）

        List<SkuDef> skus = buildSkus();
        seedSku(skus);
        seedStatusLog(skus, prevStart, lastStart);
        seedActivity(prevStart);
        seedTraffic(prevStart, lastStart, today);
        int rows = seedOrderItems(skus, prevStart, lastStart, today);
        log.info("种子数据生成完成：{} 个 SKU，order_item {} 行", skus.size(), rows);
    }

    // ---------- 商品 ----------

    private record SkuDef(String skuId, String name, String category, double price, int baseDailyQty) {
    }

    private List<SkuDef> buildSkus() {
        String[][] defs = {
                {"无糖可乐", "饮料"}, {"冰红茶", "饮料"}, {"橙汁", "饮料"}, {"矿泉水", "饮料"}, {"气泡水", "饮料"}, {"乳酸菌", "饮料"},
                {"薯片", "零食"}, {"巧克力", "零食"}, {"饼干", "零食"}, {"坚果", "零食"}, {"果冻", "零食"}, {"肉脯", "零食"},
                {"纸巾", "日用"}, {"洗衣液", "日用"}, {"牙膏", "日用"}, {"洗发水", "日用"}, {"垃圾袋", "日用"}, {"电池", "日用"},
                {"苹果", "生鲜"}, {"香蕉", "生鲜"}, {"鸡蛋", "生鲜"}, {"牛奶", "生鲜"}, {"西红柿", "生鲜"}, {"菠菜", "生鲜"},
                {"泡面", "速食"}, {"自热火锅", "速食"}, {"速冻水饺", "速食"}, {"火腿肠", "速食"}, {"面包", "速食"}, {"八宝粥", "速食"},
        };
        List<SkuDef> skus = new ArrayList<>();
        for (int i = 0; i < defs.length; i++) {
            String skuId = String.format("sku%02d", i + 1);
            if (COLA_SKU.equals(skuId)) {
                skus.add(new SkuDef(skuId, defs[i][0], defs[i][1], COLA_PRICE, COLA_DAILY_QTY));
            } else {
                double price = 4.5 + (i * 13 % 40);
                int baseQty = 6 + (i * 7 % 14);
                skus.add(new SkuDef(skuId, defs[i][0], defs[i][1], price, baseQty));
            }
        }
        return skus;
    }

    private void seedSku(List<SkuDef> skus) {
        jdbc.batchUpdate("INSERT INTO sku(sku_id, name, category) VALUES (?,?,?)",
                skus.stream().map(s -> new Object[]{s.skuId(), s.name(), s.category()}).toList());
    }

    // ---------- 事件流水 / 活动 / 流量 ----------

    private void seedStatusLog(List<SkuDef> skus, LocalDate prevStart, LocalDate lastStart) {
        List<Object[]> rows = new ArrayList<>();
        // 无糖可乐：半年前上架，上月 15 号下架
        rows.add(new Object[]{COLA_SKU, "listing", prevStart.minusMonths(6) + " 09:00:00"});
        rows.add(new Object[]{COLA_SKU, "delisting", lastStart.plusDays(COLA_DELIST_DAY - 1) + " 10:00:00"});
        // 其余 SKU：半年前上架（在架状态）
        for (SkuDef s : skus) {
            if (!s.skuId().equals(COLA_SKU)) {
                rows.add(new Object[]{s.skuId(), "listing", prevStart.minusMonths(6) + " 09:00:00"});
            }
        }
        // sku15：上月 5~9 号缺货
        rows.add(new Object[]{"sku15", "stockout_start", lastStart.plusDays(4) + " 09:00:00"});
        rows.add(new Object[]{"sku15", "stockout_end", lastStart.plusDays(8) + " 18:00:00"});
        jdbc.batchUpdate("INSERT INTO sku_status_log(sku_id, event_type, event_time) VALUES (?,?,?)", rows);
    }

    private void seedActivity(LocalDate prevStart) {
        // 前月 10~20 号一档满减活动，上月无活动（活动空窗）
        jdbc.update("INSERT INTO activity(activity_id, type, start_time, end_time, rule) VALUES (?,?,?,?,?)",
                "A001", "满减", prevStart.plusDays(9) + " 00:00:00", prevStart.plusDays(19) + " 23:59:59", "满50减10");
    }

    private void seedTraffic(LocalDate prevStart, LocalDate lastStart, LocalDate today) {
        List<Object[]> rows = new ArrayList<>();
        for (LocalDate day = prevStart; !day.isAfter(today); day = day.plusDays(1)) {
            double factor = day.isBefore(lastStart) ? 1.0 : TRAFFIC_FACTOR;
            int jitter = (day.getDayOfMonth() * 31) % 200 - 100;
            int exposure = (int) ((5000 + jitter * 3) * factor);
            int visitUv = (int) ((800 + jitter) * factor);
            rows.add(new Object[]{day.toString(), exposure, visitUv});
        }
        jdbc.batchUpdate("INSERT INTO shop_traffic_daily(stat_date, exposure, visit_uv) VALUES (?,?,?)", rows);
    }

    // ---------- 订单明细 ----------

    private int seedOrderItems(List<SkuDef> skus, LocalDate prevStart, LocalDate lastStart, LocalDate today) {
        LocalDate activityStart = prevStart.plusDays(9);
        LocalDate activityEnd = prevStart.plusDays(19);
        LocalDate colaDelistDay = lastStart.plusDays(COLA_DELIST_DAY - 1);
        LocalDate stockoutStart = lastStart.plusDays(4);
        LocalDate stockoutEnd = lastStart.plusDays(8);

        List<Object[]> batch = new ArrayList<>(20_000);
        long seq = 0;
        for (LocalDate day = prevStart; !day.isAfter(today); day = day.plusDays(1)) {
            // 上月 1 号起（含当月）进入「下滑期」：普通 SKU 销量打折、流量走低
            boolean declinePeriod = !day.isBefore(lastStart);
            for (int i = 0; i < skus.size(); i++) {
                SkuDef sku = skus.get(i);
                int dailyQty = dailyQty(sku, i, day, declinePeriod, colaDelistDay, stockoutStart, stockoutEnd);
                double price = effectivePrice(sku, day, lastStart);
                // 拆成若干订单，每单 1~3 件（确定性轮转）
                int remaining = dailyQty;
                int k = 0;
                while (remaining > 0) {
                    int qty = Math.min(remaining, (k % 3) + 1);
                    remaining -= qty;
                    seq++;
                    String ts = day + String.format(" %02d:%02d:00", 8 + (int) (seq % 12), (int) (seq * 7 % 60));
                    String activityId = (!day.isBefore(activityStart) && !day.isAfter(activityEnd) && seq % 3 == 0)
                            ? "A001" : null;
                    batch.add(new Object[]{
                            "O" + seq, sku.skuId(), ts, qty,
                            round2(qty * price), price,
                            activityId, seq % 2 == 0 ? "外卖" : "到店", "u" + (seq % 200)});
                }
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO order_item(order_id, sku_id, ts, qty, amount, price, activity_id, channel, user_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                batch);
        return batch.size();
    }

    /** 某 SKU 某天的售价：sku08 自上月 1 号起涨价 15% 并延续 */
    private double effectivePrice(SkuDef sku, LocalDate day, LocalDate lastStart) {
        if (PRICE_UP_SKU.equals(sku.skuId()) && !day.isBefore(lastStart)) {
            return round2(sku.price() * PRICE_UP_FACTOR);
        }
        return sku.price();
    }

    /** 某 SKU 某天的销量（件），确定性分布 */
    private int dailyQty(SkuDef sku, int idx, LocalDate day, boolean declinePeriod,
                         LocalDate colaDelistDay, LocalDate stockoutStart, LocalDate stockoutEnd) {
        // 无糖可乐：下架日及之后（含当月）归零
        if (sku.skuId().equals(COLA_SKU) && !day.isBefore(colaDelistDay)) {
            return 0;
        }
        // sku15：缺货期间归零
        if (sku.skuId().equals("sku15") && !day.isBefore(stockoutStart) && !day.isAfter(stockoutEnd)
                && day.getMonth() == stockoutStart.getMonth() && day.getYear() == stockoutStart.getYear()) {
            return 0;
        }
        int base = sku.baseDailyQty();
        if (declinePeriod && !sku.skuId().equals(COLA_SKU)) {
            base = (int) Math.round(base * LAST_MONTH_FACTOR);
        }
        // ±2 件的确定性日波动
        int jitter = ((day.getDayOfMonth() * 7 + idx * 13) % 5) - 2;
        return Math.max(0, base + jitter);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
