package com.merchant.analysis.time;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeResolverTest {
    private final TimeResolver resolver = new TimeResolver(
            Clock.fixed(Instant.parse("2026-11-02T16:30:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Shanghai"), null);
    private final TimeContext context = resolver.capture();

    @Test void usesBusinessZoneAndYesterday() {
        assertEquals(new TimeContext("2026-11-03", "2026-11-02", "Asia/Shanghai"), context);
    }

    @ParameterizedTest
    @CsvSource({
        "day,-1,2026-11-02,2026-11-03,2026-11-01,2026-11-02",
        "week,-1,2026-10-26,2026-11-02,2026-10-19,2026-10-26",
        "week,0,2026-11-02,2026-11-03,2026-10-26,2026-10-27",
        "month,-1,2026-10-01,2026-11-01,2026-09-01,2026-10-01",
        "month,0,2026-11-01,2026-11-03,2026-10-01,2026-10-03",
        "quarter,-1,2026-07-01,2026-10-01,2026-04-01,2026-07-01",
        "year,-1,2025-01-01,2026-01-01,2024-01-01,2025-01-01"
    })
    void calendarPeriodsAndDefaultBaselines(String unit, int offset, String start, String end, String bs, String be) {
        ResolvedTime time = resolver.resolvePeriods(TimeSpec.calendar(unit, offset), null, context);
        assertEquals(new DateRange(start, end), time.current());
        assertEquals(new DateRange(bs, be), time.baseline());
    }

    @Test void recentDaysEndsAtDataCutoffAndComparesEqualLength() {
        ResolvedTime time = resolver.resolvePeriods(TimeSpec.recentDays(7), null, context);
        assertEquals(new DateRange("2026-10-27", "2026-11-03"), time.current());
        assertEquals(new DateRange("2026-10-20", "2026-10-27"), time.baseline());
    }

    @Test void absoluteRangeIncludesEndDateAndHonorsExplicitComparison() {
        ResolvedTime time = resolver.resolvePeriods(TimeSpec.dates("2026-10-10", "2026-10-20"),
                TimeSpec.dates("2025-10-10", "2025-10-20"), context);
        assertEquals(new DateRange("2026-10-10", "2026-10-21"), time.current());
        assertEquals(new DateRange("2025-10-10", "2025-10-21"), time.baseline());
        ResolvedTime automatic = resolver.resolvePeriods(TimeSpec.dates("2026-10-10", "2026-10-20"), null, context);
        assertEquals(new DateRange("2026-09-29", "2026-10-10"), automatic.baseline());
    }

    @Test void leapDayAndShortPreviousMonth() {
        TimeContext leap = new TimeContext("2024-03-01", "2024-02-29", "Asia/Shanghai");
        assertEquals(new DateRange("2024-02-01", "2024-03-01"), resolver.resolve(TimeSpec.calendar("month", -1), leap));
        assertEquals(29, resolver.resolve(TimeSpec.calendar("month", -1), leap).dayCount());
        TimeContext march = new TimeContext("2026-03-31", "2026-03-30", "Asia/Shanghai");
        assertEquals(new DateRange("2026-02-01", "2026-03-01"),
                resolver.resolvePeriods(TimeSpec.calendar("month", 0), null, march).baseline());
    }

    @Test void firstDayOfMonthHasNoCurrentMonthDailyData() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(TimeSpec.calendar("month", 0),
                new TimeContext("2026-11-01", "2026-10-31", "Asia/Shanghai")));
    }

    @Test void rejectsTodayFutureAndIncompleteHistoricalPeriod() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(TimeSpec.calendar("day", 0), context));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(TimeSpec.dates("2026-11-02", "2026-11-03"), context));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolvePeriods(TimeSpec.recentDays(1),
                TimeSpec.dates("2026-11-03", "2026-11-03"), context));
        TimeContext delayed = new TimeContext("2026-11-03", "2026-10-30", "Asia/Shanghai");
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(TimeSpec.calendar("month", -1), delayed));
    }

    @Test void configuredCutoffControlsRecentDays() {
        TimeResolver delayed = new TimeResolver(Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC),
                ZoneId.of("Asia/Shanghai"), LocalDate.of(2026, 11, 1));
        assertEquals(new DateRange("2026-10-26", "2026-11-02"), delayed.resolve(TimeSpec.recentDays(7), delayed.capture()));
    }

    @Test void timeIntentAndFrozenRangesRoundTripWithoutJavaTimeModules() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (TimeSpec spec : new TimeSpec[]{TimeSpec.calendar("month", -1), TimeSpec.recentDays(7),
                TimeSpec.dates("2026-10-10", "2026-10-20")}) {
            assertEquals(spec, mapper.readValue(mapper.writeValueAsString(spec), TimeSpec.class));
        }
        ResolvedTime time = resolver.resolvePeriods(TimeSpec.recentDays(7), null, context);
        assertEquals(time, mapper.readValue(mapper.writeValueAsString(time), ResolvedTime.class));
        assertEquals(TimeSpec.calendar("month", -1), mapper.readValue("\"last_month\"", TimeSpec.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"type\":\"recent_days\",\"days\":0}",
        "{\"type\":\"recent_days\",\"days\":1.5}",
        "{\"type\":\"recent_days\",\"days\":\"7\"}",
        "{\"type\":\"recent_days\",\"days\":true}",
        "{\"type\":\"recent_days\",\"days\":7,\"unit\":null}",
        "{\"type\":\"calendar_period\",\"unit\":\"hour\",\"offset\":-1}",
        "{\"type\":\"calendar_period\",\"unit\":\"month\",\"offset\":1}",
        "{\"type\":\"calendar_period\",\"unit\":\"month\"}",
        "{\"type\":\"date_range\",\"start\":\"2026-02-30\",\"end\":\"2026-03-01\"}",
        "{\"type\":\"date_range\",\"start\":\"2026-10-20\",\"end\":\"2026-10-10\"}",
        "{\"type\":\"date_range\",\"start\":\"2026-10-10T00:00:00\",\"end\":\"2026-10-20\"}",
        "{\"type\":\"rolling_window\",\"days\":7}"
    })
    void rejectsMalformedTimeDefinitions(String json) {
        assertThrows(Exception.class, () -> new ObjectMapper().readValue(json, TimeSpec.class));
    }
}
