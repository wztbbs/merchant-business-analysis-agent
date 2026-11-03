package com.merchant.analysis.time;

/** 具体日期区间：[start, endExclusive)，格式 yyyy-MM-dd */
public record DateRange(String start, String endExclusive) {

    public DateRange {
        if (!parseDate(start).isBefore(parseDate(endExclusive)))
            throw new IllegalArgumentException("日期区间必须满足 start < endExclusive");
    }

    static java.time.LocalDate parseDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("日期必须使用 yyyy-MM-dd 格式");
        java.time.LocalDate date = java.time.LocalDate.parse(value);
        if (date.getYear() < 1) throw new IllegalArgumentException("年份必须在 0001 到 9999 之间");
        return date;
    }

    public long dayCount() {
        return java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(start), java.time.LocalDate.parse(endExclusive));
    }


    /** 区间起点时间戳串（与 order_item.ts 比较用） */
    public String startTs() {
        return start + " 00:00:00";
    }

    /** 区间终点（开区间）时间戳串 */
    public String endTs() {
        return endExclusive + " 00:00:00";
    }
}
