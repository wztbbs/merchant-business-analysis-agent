package com.merchant.analysis.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** 执行器只读取已解析的显式日期参数，不再从共享上下文隐式取得时间。 */
public final class TimeParameters {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private TimeParameters() {}

    public static DateRange current(Map<String, Object> params) { return read(params, "time_range"); }
    public static DateRange baseline(Map<String, Object> params) { return read(params, "baseline"); }

    private static DateRange read(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) throw new IllegalArgumentException("缺少已解析的日期参数: " + key);
        return value instanceof DateRange range ? range : MAPPER.convertValue(value, DateRange.class);
    }
}
