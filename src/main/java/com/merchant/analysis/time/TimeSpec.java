package com.merchant.analysis.time;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.Set;

/** 日粒度时间意图；date_range.end 包含当天。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = TimeSpec.Deserializer.class)
public record TimeSpec(String type, String unit, Integer offset, Integer days, String start, String end) {
    public TimeSpec {
        if (type == null) throw new IllegalArgumentException("time.type 不能为空");
        switch (type) {
            case "calendar_period" -> {
                if (!Set.of("day", "week", "month", "quarter", "year").contains(unit == null ? "" : unit)
                        || offset == null || offset > 0 || days != null || start != null || end != null)
                    throw new IllegalArgumentException("calendar_period 需要合法 unit 和非正整数 offset，不能混入其他类型字段");
            }
            case "recent_days" -> {
                if (days == null || days <= 0 || unit != null || offset != null || start != null || end != null)
                    throw new IllegalArgumentException("recent_days 只接受正整数 days");
            }
            case "date_range" -> {
                if (start == null || end == null || unit != null || offset != null || days != null)
                    throw new IllegalArgumentException("date_range 需要 start 和 end，不能混入其他类型字段");
                if (DateRange.parseDate(end).isBefore(DateRange.parseDate(start)))
                    throw new IllegalArgumentException("结束日期不能早于开始日期");
            }
            default -> throw new IllegalArgumentException("未知 time.type: " + type);
        }
    }
    public static TimeSpec calendar(String unit, int offset) {
        return new TimeSpec("calendar_period", unit, offset, null, null, null);
    }
    public static TimeSpec recentDays(int days) {
        return new TimeSpec("recent_days", null, null, days, null, null);
    }
    public static TimeSpec dates(String start, String end) {
        return new TimeSpec("date_range", null, null, null, start, end);
    }
    /** 兼容已有运行记录中的旧月份标识。 */
    public static TimeSpec legacy(String value) {
        return switch (value) {
            case "last_month" -> calendar("month", -1);
            case "previous_month" -> calendar("month", -2);
            case "this_month" -> calendar("month", 0);
            default -> throw new IllegalArgumentException("未知旧时间标识: " + value);
        };
    }
    public static final class Deserializer extends JsonDeserializer<TimeSpec> {
        @Override
        public TimeSpec deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            try {
                if (node.isTextual()) return legacy(node.textValue());
                if (!node.isObject()) throw new IllegalArgumentException("时间定义必须为对象");
                String type = text(node, "type");
                Set<String> fields = switch (type) {
                    case "calendar_period" -> Set.of("type", "unit", "offset");
                    case "recent_days" -> Set.of("type", "days");
                    case "date_range" -> Set.of("type", "start", "end");
                    default -> throw new IllegalArgumentException("未知 time.type: " + type);
                };
                node.fieldNames().forEachRemaining(field -> {
                    if (!fields.contains(field)) throw new IllegalArgumentException("时间定义含未知字段: " + field);
                });
                return switch (type) {
                    case "calendar_period" -> calendar(text(node, "unit"), integer(node, "offset"));
                    case "recent_days" -> recentDays(integer(node, "days"));
                    default -> dates(text(node, "start"), text(node, "end"));
                };
            } catch (RuntimeException e) {
                return (TimeSpec) context.handleWeirdStringValue(TimeSpec.class, node.toString(), e.getMessage());
            }
        }
        private static String text(JsonNode node, String field) {
            if (!node.path(field).isTextual()) throw new IllegalArgumentException(field + " 必须是字符串");
            return node.get(field).textValue();
        }
        private static int integer(JsonNode node, String field) {
            JsonNode value = node.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException(field + " 必须是整数");
            return value.intValue();
        }
    }
}
