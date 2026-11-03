package com.merchant.analysis.time;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** 自然周期以业务日期为锚点，最近 N 天以数据截止日为终点。 */
@Component
public class TimeResolver {
    private final Clock clock;
    private final ZoneId zone;
    private final LocalDate configuredDataThrough;

    public TimeResolver() { this(Clock.systemUTC(), ZoneId.of("Asia/Shanghai"), null); }

    @Autowired
    public TimeResolver(@Value("${business.time-zone:Asia/Shanghai}") String zone,
                        @Value("${business.data-through:}") String dataThrough) {
        this(Clock.systemUTC(), ZoneId.of(zone), dataThrough.isBlank() ? null : LocalDate.parse(dataThrough));
    }
    public TimeResolver(Clock clock, ZoneId zone, LocalDate dataThrough) {
        this.clock = clock;
        this.zone = zone;
        this.configuredDataThrough = dataThrough;
    }
    public TimeContext capture() {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate through = configuredDataThrough == null ? today.minusDays(1) : configuredDataThrough;
        return new TimeContext(today.toString(), through.toString(), zone.getId());
    }
    public ResolvedTime resolvePeriods(TimeSpec current, TimeSpec baseline, TimeContext context) {
        TimeSpec spec = current == null ? TimeSpec.calendar("month", -1) : current;
        DateRange range = resolve(spec, context);
        DateRange comparison = baseline == null ? defaultBaseline(spec, range) : resolve(baseline, context);
        return new ResolvedTime(context, range, comparison);
    }
    public DateRange resolve(TimeSpec spec, TimeContext context) {
        if (spec == null) throw new IllegalArgumentException("时间定义不能为空");
        LocalDate reference = LocalDate.parse(context.reference_date());
        LocalDate limit = LocalDate.parse(context.data_through()).plusDays(1);
        LocalDate start;
        LocalDate end;
        switch (spec.type()) {
            case "calendar_period" -> {
                LocalDate origin = switch (spec.unit()) {
                    case "day" -> reference;
                    case "week" -> reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    case "month" -> reference.withDayOfMonth(1);
                    case "quarter" -> reference.withMonth((reference.getMonthValue() - 1) / 3 * 3 + 1).withDayOfMonth(1);
                    default -> reference.withDayOfYear(1);
                };
                start = shift(origin, spec.unit(), spec.offset());
                end = shift(start, spec.unit(), 1);
                // 本周期默认截止已有数据；历史完整周期不能静默截短。
                if (spec.offset() == 0 && end.isAfter(limit)) end = limit;
            }
            case "recent_days" -> {
                end = limit;
                start = end.minusDays(spec.days());
            }
            default -> {
                start = LocalDate.parse(spec.start());
                end = LocalDate.parse(spec.end()).plusDays(1);
            }
        }
        if (end.isAfter(limit)) throw new IllegalArgumentException("请求日期超过数据截止日 " + context.data_through());
        if (!start.isBefore(end)) throw new IllegalArgumentException("请求周期暂无可用日数据，数据截至 " + context.data_through());
        return new DateRange(start.toString(), end.toString());
    }
    private DateRange defaultBaseline(TimeSpec spec, DateRange current) {
        LocalDate start = LocalDate.parse(current.start());
        if ("calendar_period".equals(spec.type())) {
            LocalDate previousStart = shift(start, spec.unit(), -1);
            LocalDate previousEnd = start;
            if (spec.offset() == 0) {
                LocalDate sameDaysEnd = previousStart.plusDays(current.dayCount());
                if (sameDaysEnd.isBefore(previousEnd)) previousEnd = sameDaysEnd;
            }
            return new DateRange(previousStart.toString(), previousEnd.toString());
        }
        return new DateRange(start.minusDays(current.dayCount()).toString(), start.toString());
    }
    private static LocalDate shift(LocalDate date, String unit, int count) {
        return switch (unit) {
            case "day" -> date.plusDays(count);
            case "week" -> date.plusWeeks(count);
            case "month" -> date.plusMonths(count);
            case "quarter" -> date.plusMonths((long) count * 3);
            default -> date.plusYears(count);
        };
    }
}
