package com.merchant.analysis.time;

import java.time.LocalDate;

/** 保存实际查询区间，重试时不能重新解释相对日期。 */
public record ResolvedTime(TimeContext context, DateRange current, DateRange baseline) {
    public ResolvedTime {
        if (context == null || current == null || baseline == null)
            throw new IllegalArgumentException("已解析时间需要上下文、观察期和基线期");
        String limit = LocalDate.parse(context.data_through()).plusDays(1).toString();
        if (current.endExclusive().compareTo(limit) > 0 || baseline.endExclusive().compareTo(limit) > 0)
            throw new IllegalArgumentException("查询区间不能超过数据截止日期");
    }
}
