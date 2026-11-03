package com.merchant.analysis.time;

import java.time.ZoneId;

/** 一次分析固定的业务日期与数据截止日，随运行记录持久化。 */
public record TimeContext(String reference_date, String data_through, String timezone) {
    public TimeContext {
        ZoneId.of(timezone);
        if (!DateRange.parseDate(data_through).isBefore(DateRange.parseDate(reference_date)))
            throw new IllegalArgumentException("日数据截止日期必须早于业务当前日期");
    }
}
