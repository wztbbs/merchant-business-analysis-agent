package com.merchant.analysis.time;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 逻辑时间解析：把 last_month / previous_month 等解析为具体日期区间（基于 LocalDate.now()）。
 * 种子数据用相对日期生成，因此任何时候启动「上月/前月」都有数据。
 */
@Component
public class TimeResolver {

    public DateRange resolve(String logic) {
        LocalDate firstOfThisMonth = LocalDate.now().withDayOfMonth(1);
        return switch (logic == null ? "last_month" : logic) {
            case "last_month" ->       // 上月（最近一个完整月）
                    new DateRange(firstOfThisMonth.minusMonths(1).toString(), firstOfThisMonth.toString());
            case "previous_month" ->   // 前月（上月的再上一个月，作为对比基线）
                    new DateRange(firstOfThisMonth.minusMonths(2).toString(), firstOfThisMonth.minusMonths(1).toString());
            case "this_month" ->
                    new DateRange(firstOfThisMonth.toString(), firstOfThisMonth.plusMonths(1).toString());
            default -> throw new IllegalArgumentException("未知逻辑时间: " + logic);
        };
    }
}
