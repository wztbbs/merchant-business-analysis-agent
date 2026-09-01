package com.merchant.analysis.time;

/** 具体日期区间：[start, endExclusive)，格式 yyyy-MM-dd */
public record DateRange(String start, String endExclusive) {

    /** 区间起点时间戳串（与 order_item.ts 比较用） */
    public String startTs() {
        return start + " 00:00:00";
    }

    /** 区间终点（开区间）时间戳串 */
    public String endTs() {
        return endExclusive + " 00:00:00";
    }
}
